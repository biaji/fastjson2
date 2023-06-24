package com.alibaba.fastjson2.internal.processor;

import com.alibaba.fastjson2.annotation.JSONCompiled;
import com.alibaba.fastjson2.reader.ObjectReaderAdapter;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.fastjson2.internal.processor.CodeGenUtils.*;
import static com.alibaba.fastjson2.internal.processor.JavacTreeUtils.*;

@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({
        "com.alibaba.fastjson2.annotation.JSONCompiled",
        "com.alibaba.fastjson2.annotation.JSONBuilder",
        "com.alibaba.fastjson2.annotation.JSONCreator",
        "com.alibaba.fastjson2.annotation.JSONField",
        "com.alibaba.fastjson2.annotation.JSONType"
})
public class JSONCompiledAnnotationProcessor2
        extends AbstractProcessor {
    private Messager messager;
    private JavacTrees javacTrees;
    private Names names;
    private Map<String, Integer> count;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.javacTrees = JavacTrees.instance(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.names = Names.instance(context);
        initialize(TreeMaker.instance(context), names, processingEnv.getElementUtils());
        count = new HashMap<>();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Analysis analysis = new Analysis(processingEnv);
        Set<? extends Element> compiledJsons = roundEnv.getElementsAnnotatedWith(analysis.jsonCompiledElement);
        if (!compiledJsons.isEmpty()) {
            analysis.processAnnotation(analysis.compiledJsonType, compiledJsons);
        }

        Map<String, StructInfo> structs = analysis.analyze();
        Set<? extends Element> elementsAnnotatedWith = roundEnv.getElementsAnnotatedWith(JSONCompiled.class);
        elementsAnnotatedWith.stream().forEach(element -> {
            StructInfo info = structs.get(element.toString());
            java.util.List<AttributeInfo> fields = info.getReaderAttributes();
            int fieldsSize = fields.size();
            Class superClass = getSuperClass(fields.size());
            // generate imports
            Set<JCTree> imports = genImports(element, false, new String[]{});
            String importsStr = imports.stream().filter(t -> t.getTag().equals(JCTree.Tag.IMPORT)).map(t2 -> t2.toString()).collect(Collectors.joining());

            JCTree tree = javacTrees.getTree(element);
            pos(tree.pos);
            tree.accept(new TreeTranslator() {
                @Override
                public void visitClassDef(JCTree.JCClassDecl beanClassDecl) {
                    super.visitClassDef(beanClassDecl);
                    String classNamePath = beanClassDecl.sym.toString();
                    if (element.toString().equals(classNamePath)) {
                        // initialization
                        JCTree.JCExpression beanType = qualIdent(classNamePath);
                        JCTree.JCNewClass beanNew = newClass(null, null, beanType, List.nil(), null);
                        JCTree.JCIdent objectType = ident("Object");
                        JCTree.JCVariableDecl featuresVar = defVar(Flags.PARAMETER, "features", type(TypeTag.LONG));

                        // generate inner class
                        JCTree.JCClassDecl innerClass = genInnerClass(beanClassDecl, superClass);

                        // generate fields if necessary
                        final boolean generatedFields = fieldsSize < 128;
                        if (generatedFields) {
                            innerClass.defs = innerClass.defs.prependList(genFields(fields, superClass));
                        }

                        // generate constructor
                        innerClass.defs = innerClass.defs.append(genConstructor(beanType, beanNew, fields, superClass, generatedFields));

                        // generate createInstance
                        innerClass.defs = innerClass.defs.append(genCreateInstance(objectType, beanNew));

                        // generate readObject
                        innerClass.defs = innerClass.defs.append(genReadObject(objectType, classNamePath, beanNew, fields, info, featuresVar, false));

                        beanClassDecl.defs = beanClassDecl.defs.append(innerClass);

                        // generate source file
//                        genSource(classNamePath, info, importsStr, beanClassDecl);
                    }
                }
            });
        });
        return true;
    }

    private Set<JCTree> genImports(Element element, boolean isStatic, String... fullQualifiedNames) {
        if (fullQualifiedNames == null || fullQualifiedNames.length == 0) {
            messager.printMessage(Diagnostic.Kind.NOTE, "fullQualifiedName must not be null or empty");
        }
        messager.printMessage(Diagnostic.Kind.NOTE, String.format("add import of %s to %s", Arrays.toString(fullQualifiedNames), element.getSimpleName()));
        Set<JCTree> imports = new TreeSet<>(Comparator.comparing(JCTree::toString));
        CompilationUnitTree compilationUnit = javacTrees.getPath(element).getCompilationUnit();
        if (compilationUnit instanceof JCTree.JCCompilationUnit) {
            JCTree.JCCompilationUnit jcCompilationUnit = (JCTree.JCCompilationUnit) compilationUnit;
            imports.addAll(jcCompilationUnit.defs);
        }
        return imports;
    }

    private List<JCTree> genFields(java.util.List<AttributeInfo> fields, Class superClass) {
        ListBuffer<JCTree> stmts = new ListBuffer<>();
        int fieldsSize = fields.size();
        JCTree.JCExpression fieldReaderType = qualIdent("com.alibaba.fastjson2.reader.FieldReader");
        JCTree.JCExpression objectReaderType = qualIdent("com.alibaba.fastjson2.reader.ObjectReader");
        if (superClass == ObjectReaderAdapter.class) {
            for (int i = 0; i < fieldsSize; i++) {
                JCTree.JCVariableDecl var = defVar(Flags.PUBLIC,
                        fieldReader(i),
                        fieldReaderType);
                stmts.append(var);
            }

            for (int i = 0; i < fieldsSize; i++) {
                JCTree.JCVariableDecl var = defVar(Flags.PUBLIC,
                        fieldObjectReader(i),
                        objectReaderType);
                stmts.append(var);
            }
        }
        for (int i = 0; i < fieldsSize; i++) {
            AttributeInfo field = fields.get(i);
            String fieldType = field.type.toString();
            if (fieldType.startsWith("java.util.List<")) {
                stmts.append(defVar(Flags.PRIVATE, fieldItemObjectReader(i), objectReaderType));
            }
        }
        return stmts.toList();
    }

    /**
     * @param beanClassDecl
     * @param superClass
     * @return
     */
    private JCTree.JCClassDecl genInnerClass(JCTree.JCClassDecl beanClassDecl, Class superClass) {
        JCTree.JCClassDecl innerClass = defClass(Flags.PRIVATE,
                beanClassDecl.getSimpleName() + "_FASTJOSNReader",
                null,
                qualIdent(superClass.getName()),
                null,
                null);
        return innerClass;
    }

    private JCTree.JCMethodDecl genConstructor(JCTree.JCExpression beanType, JCTree.JCNewClass beanNew, java.util.List<AttributeInfo> fields, Class superClass, boolean generatedFields) {
        JCTree.JCLiteral nullLiteral = literal(TypeTag.BOT, null);
        JCTree.JCLambda lambda = lambda(List.nil(), beanNew);
        JCTree.JCExpression fieldReaderType = qualIdent("com.alibaba.fastjson2.reader.FieldReader");
        ListBuffer fieldReaders = new ListBuffer<>();
        JCTree.JCExpression objectReaderCreatorType = qualIdent("com.alibaba.fastjson2.reader.ObjectReaderCreator");
        JCTree.JCFieldAccess instanceField = field(objectReaderCreatorType, "INSTANCE");
        JCTree.JCExpression beanUtilsType = qualIdent("com.alibaba.fastjson2.util.BeanUtils");
        int fieldsSize = fields.size();
        for (int i = 0; i < fieldsSize; i++) {
            AttributeInfo field = fields.get(i);
            JCTree.JCMethodInvocation readerMethod = null;
            if (field.setMethod != null) {
                JCTree.JCMethodInvocation getSetterMethod = method(field(beanUtilsType, "getSetter"), List.of(field(beanType, names._class), literal(field.setMethod.getSimpleName().toString())));
                readerMethod = method(field(instanceField, "createFieldReader"), List.of(literal(field.name), getSetterMethod));
            } else if (field.field != null) {
                JCTree.JCMethodInvocation getDeclaredFieldMethod = method(field(beanUtilsType, "getDeclaredField"), List.of(field(beanType, names._class), literal(field.name)));
                readerMethod = method(field(instanceField, "createFieldReader"), List.of(literal(field.name), getDeclaredFieldMethod));
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "not implemented yet");
            }
            if (readerMethod != null) {
                fieldReaders.append(readerMethod);
            }
        }
        JCTree.JCNewArray fieldReadersArray = newArray(fieldReaderType, null, List.from(fieldReaders));
        JCTree.JCMethodInvocation superMethod = method(
                ident(names._super),
                List.of(field(beanType, names._class), nullLiteral, nullLiteral, literal(TypeTag.LONG, 0), nullLiteral, lambda, nullLiteral, fieldReadersArray));
        ListBuffer<JCTree.JCStatement> stmts = new ListBuffer<>();
        stmts.append(exec(superMethod));
        // initialize fields if necessary
        if (superClass == ObjectReaderAdapter.class && generatedFields) {
            stmts.appendList(genInitFields(fieldsSize, fieldReadersArray));
        }
        return defMethod(Flags.PUBLIC, names.init, type(TypeTag.VOID), List.nil(), List.nil(), List.nil(), block(0, stmts.toList()), null);
    }

    private List<JCTree.JCStatement> genInitFields(int fieldsSize, JCTree.JCNewArray fieldReadersArray) {
        ListBuffer<JCTree.JCStatement> stmts = new ListBuffer<>();
        for (int i = 0; i < fieldsSize; i++) {
            JCTree.JCIdent fieldReaderIdent = ident(fieldReader(i));
            stmts.append(exec(assign(fieldReaderIdent, indexed(fieldReadersArray, literal(TypeTag.INT, i)))));
        }
        return stmts.toList();
    }

    private JCTree.JCMethodDecl genCreateInstance(JCTree.JCIdent objectType, JCTree.JCNewClass beanNew) {
        JCTree.JCVariableDecl featuresVar = defVar(Flags.PARAMETER, "features", type(TypeTag.LONG));
        return defMethod(Flags.PUBLIC, "createInstance", objectType, List.nil(), List.of(featuresVar), List.nil(), block(0, List.of(defReturn(beanNew))), null);
    }

    private JCTree.JCMethodDecl genReadObject(JCTree.JCIdent objectType, String classNamePath, JCTree.JCNewClass beanNew, java.util.List<AttributeInfo> fields, StructInfo info, JCTree.JCVariableDecl featuresVar, boolean isJsonb) {
        JCTree.JCExpression jsonReaderType = qualIdent("com.alibaba.fastjson2.JSONReader");
        JCTree.JCVariableDecl jsonReaderVar = defVar(Flags.PARAMETER, "jsonReader", jsonReaderType);
        JCTree.JCExpression typeType = qualIdent("java.lang.reflect.Type");
        JCTree.JCVariableDecl typeVar = defVar(Flags.PARAMETER, "type", typeType);
        JCTree.JCVariableDecl fieldNameVar = defVar(Flags.PARAMETER, "fieldName", objectType);
        JCTree.JCReturn nullReturn = defReturn(literal(TypeTag.BOT, null));
        ListBuffer<JCTree.JCStatement> readObjectBody = new ListBuffer<>();

        JCTree.JCIdent jsonReaderIdent = ident(jsonReaderVar.name);
        JCTree.JCMethodInvocation nextIfNullMethod = method(field(jsonReaderIdent, "nextIfNull"));
        readObjectBody.append(defIf(nextIfNullMethod, block(0, List.of(nullReturn)), null));
        readObjectBody.append(exec(method(field(jsonReaderIdent, "nextIfObjectStart"))));
        JCTree.JCVariableDecl objectVar = defVar(Flags.PARAMETER, "object", qualIdent(classNamePath), beanNew);
        readObjectBody.append(objectVar);

        JCTree.JCLabeledStatement forLabel = label("_for", null);
        JCTree.JCForLoop forLoop = forLoop(null, null, null, null);
        ListBuffer<JCTree.JCStatement> forBody = new ListBuffer<>();
        JCTree.JCIf nextIfObjectEndIf = defIf(method(field(jsonReaderIdent, "nextIfObjectEnd")), block(0, List.of(defBreak(forLabel))), null);
        forBody.append(nextIfObjectEndIf);
        JCTree.JCFieldAccess readFieldNameHashCode = field(jsonReaderIdent, "readFieldNameHashCode");
        JCTree.JCTypeCast hashCode64Cast = cast(type(TypeTag.LONG), method(readFieldNameHashCode));
        JCTree.JCVariableDecl hashCode64Var = defVar(Flags.PARAMETER, "hashCode64", type(TypeTag.LONG), hashCode64Cast);
        forBody.append(hashCode64Var);
        JCTree.JCExpression hashCode64 = ident(hashCode64Var.name);
        forBody.append(defIf(binary(JCTree.Tag.EQ, literal(TypeTag.LONG, 0), hashCode64), block(0, List.of(defBreak(forLabel))), null));
        JCTree.JCIdent objectIdent = ident("object");

        int fieldsSize = fields.size();
        if (fieldsSize <= 6) {
            for (int i = 0; i < fieldsSize; ++i) {
                AttributeInfo field = fields.get(i);
                forBody.appendList(genReadFieldValue(jsonReaderIdent, field, i, info, field.nameHashCode, hashCode64, forLabel, objectIdent, true, isJsonb));
            }
        } else {
            Map<Integer, java.util.List<Long>> map = new TreeMap();
            Map<Long, AttributeInfo> mapping = new TreeMap();
            Map<Long, Integer> mappingIndex = new TreeMap();

            for (int i = 0; i < fieldsSize; i++) {
                AttributeInfo field = fields.get(i);
                long fieldNameHash = field.nameHashCode;
                int hashCode32 = (int) (fieldNameHash ^ (fieldNameHash >>> 32));
                java.util.List<Long> hashCode64List = map.computeIfAbsent(hashCode32, k -> new ArrayList<>());
                hashCode64List.add(fieldNameHash);
                mapping.put(fieldNameHash, field);
                mappingIndex.put(fieldNameHash, i);
            }

            int[] hashCode32Keys = new int[map.size()];
            {
                int off = 0;
                for (Integer key : map.keySet()) {
                    hashCode32Keys[off++] = key;
                }
            }
            Arrays.sort(hashCode32Keys);

            JCTree.JCVariableDecl hashCode32Var = getHashCode32Var(hashCode64);
            forBody.append(hashCode32Var);
            JCTree.JCExpression hashCode32 = ident(hashCode32Var.name);

            ListBuffer<JCTree.JCCase> cases = new ListBuffer<>();
            for (int i = 0; i < fieldsSize; i++) {
                JCTree.JCLabeledStatement iLabel = label(i + "", null);
                java.util.List<Long> hashCode64Array = map.get(hashCode32Keys[i]);
                List<JCTree.JCStatement> stmts = List.nil();
                Long fieldNameHash = null;
                if (hashCode64Array.size() == 1 && hashCode64Array.get(0) == hashCode32Keys[i]) {
                    fieldNameHash = hashCode64Array.get(0);
                    int index = mappingIndex.get(fieldNameHash);
                    AttributeInfo field = mapping.get(fieldNameHash);
                    stmts = stmts.appendList(genReadFieldValue(jsonReaderIdent, field, index, info, field.nameHashCode, hashCode64, forLabel, objectIdent, false, isJsonb));
                    stmts.append(defContinue(forLabel));
                } else {
                    for (int j = 0; j < hashCode64Array.size(); ++j) {
                        fieldNameHash = hashCode64Array.get(j);
                        int index = mappingIndex.get(fieldNameHash);
                        AttributeInfo field = mapping.get(fieldNameHash);
                        List<JCTree.JCStatement> stmtsIf = genReadFieldValue(jsonReaderIdent, field, index, info, field.nameHashCode, hashCode64, forLabel, objectIdent, false, isJsonb);
                        stmts = stmts.append(defIf(binary(JCTree.Tag.EQ, hashCode64, literal(TypeTag.LONG, fieldNameHash)), block(0, stmtsIf), null));
                        stmts.append(defContinue(forLabel));
                    }
                    stmts.append(defBreak(iLabel));
                }
                cases.append(defCase(getHashCode32Var(literal(TypeTag.LONG, fieldNameHash)).getInitializer(), stmts));
            }
            forBody.append(defSwitch(hashCode32, cases.toList()));
        }

        JCTree.JCFieldAccess processExtraField = field(ident(names._this), "processExtra");
        forBody.append(exec(method(processExtraField, List.of(jsonReaderIdent, objectIdent))));

        forLoop.body = block(0, forBody.toList());
        forLabel.body = forLoop;

        readObjectBody.append(forLabel);
        readObjectBody.append(defReturn(objectIdent));
        return defMethod(Flags.PUBLIC, "readObject", objectType, List.nil(), List.of(jsonReaderVar, typeVar, fieldNameVar, featuresVar), List.nil(), block(0, readObjectBody.toList()), null);
    }

    private JCTree.JCVariableDecl getHashCode32Var(JCTree.JCExpression hashCode64) {
        JCTree.JCBinary usrBinary = binary(JCTree.Tag.USR, hashCode64, literal(TypeTag.INT, 32));
        JCTree.JCPrimitiveTypeTree intType = type(TypeTag.INT);
        JCTree.JCTypeCast hashCode32Cast = cast(intType, binary(JCTree.Tag.BITXOR, hashCode64, parens(usrBinary)));
        return defVar(Flags.PARAMETER, "hashCode32", intType, hashCode32Cast);
    }

    private List<JCTree.JCStatement> genReadFieldValue(
            JCTree.JCIdent jsonReaderIdent,
            AttributeInfo field,
            int i,
            StructInfo info,
            long fieldNameHash,
            JCTree.JCExpression readFieldNameHashCodeMethod,
            JCTree.JCLabeledStatement forLabel,
            JCTree.JCIdent objectIdent,
            boolean isIf,
            boolean isJsonb) {
        ListBuffer<JCTree.JCStatement> stmts = new ListBuffer<>();
        String type = field.type.toString();
        JCTree.JCLiteral nullLiteral = literal(TypeTag.BOT, null);
        JCTree.JCExpression valueExpr;
        switch (type) {
            case "boolean":
                valueExpr = method(field(jsonReaderIdent, "readBoolValue"));
                break;
            case "byte":
                valueExpr = method(field(jsonReaderIdent, "readInt32Value"));
                break;
            case "short":
                JCTree.JCFieldAccess shortField = field(ident("short"), names._class);
                valueExpr = method(field(jsonReaderIdent, "readInt32Value"), List.of(shortField));
                break;
            case "int":
                valueExpr = method(field(jsonReaderIdent, "readInt32Value"));
                break;
            case "long":
                valueExpr = method(field(jsonReaderIdent, "readInt64Value"));
                break;
            case "float":
                valueExpr = method(field(jsonReaderIdent, "readFloatValue"));
                break;
            case "double":
                valueExpr = method(field(jsonReaderIdent, "readDoubleValue"));
                break;
            case "char":
                valueExpr = method(field(jsonReaderIdent, "readCharValue"));
                break;
            case "int[]":
                valueExpr = method(field(jsonReaderIdent, "readInt32ValueArray"));
                break;
            case "long[]":
                valueExpr = method(field(jsonReaderIdent, "readInt64ValueArray"));
                break;
            case "java.lang.String":
                valueExpr = method(field(jsonReaderIdent, "readString"));
                break;
            case "java.lang.Integer":
                valueExpr = method(field(jsonReaderIdent, "readInt32"));
                break;
            case "java.lang.Long":
                valueExpr = method(field(jsonReaderIdent, "readInt64"));
                break;
            case "java.lang.Float":
                valueExpr = method(field(jsonReaderIdent, "readFloat"));
                break;
            case "java.lang.readDouble":
                valueExpr = method(field(jsonReaderIdent, "readDouble"));
                break;
            case "java.math.BigDecimal":
                valueExpr = method(field(jsonReaderIdent, "readBigDecimal"));
                break;
            case "java.math.BigInteger":
                valueExpr = method(field(jsonReaderIdent, "readBigInteger"));
                break;
            case "java.util.UUID":
                valueExpr = method(field(jsonReaderIdent, "readUUID"));
                break;
            case "java.lang.String[]":
                valueExpr = method(field(jsonReaderIdent, "readStringArray"));
                break;
            case "java.time.LocalDate":
                valueExpr = method(field(jsonReaderIdent, "readLocalDate"));
                break;
            case "java.time.OffsetDateTime":
                valueExpr = method(field(jsonReaderIdent, "readOffsetDateTime"));
                break;
            default:
                JCTree.JCFieldAccess fieldReaderField = field(ident(names._this), fieldReader(i));
                if (info.referenceDetect) {
                    ListBuffer<JCTree.JCStatement> stmts1 = new ListBuffer<>();
                    JCTree.JCMethodInvocation readReferenceMethod = method(field(jsonReaderIdent, "readReference"));
                    JCTree.JCVariableDecl refVar = defVar(Flags.PARAMETER, "ref", ident("String"), readReferenceMethod);
                    stmts1.append(refVar);
                    JCTree.JCMethodInvocation addResolveTaskMethod = method(field(fieldReaderField, "addResolveTask"), List.of(jsonReaderIdent, objectIdent, ident(refVar.name)));
                    stmts1.append(exec(addResolveTaskMethod));
                    stmts1.append(defContinue(forLabel));
                    stmts.append(defIf(method(field(jsonReaderIdent, "isReference")), block(0, stmts1.toList()), null));
                }

                JCTree.JCExpression fieldValueType = getFieldValueType(type);
                JCTree.JCVariableDecl fieldValueVar = defVar(Flags.PARAMETER, field.name, fieldValueType);
                stmts.append(fieldValueVar);

                boolean list = type.startsWith("java.util.List<");
                if (list) {
                    String itemType = type.substring(15, type.length() - 1);
                    boolean itemTypeIsClass = itemType.indexOf('<') == -1;
                    if (itemTypeIsClass) {
                        JCTree.JCMethodInvocation nextIfNullMethod = method(field(jsonReaderIdent, "nextIfNull"));
                        stmts.append(defIf(nextIfNullMethod,
                                block(0, List.of(exec(assign(ident(fieldValueVar.name), nullLiteral)))),
                                block(0, List.of(exec(assign(ident(fieldValueVar.name), newClass(null, null, qualIdent("java.util.ArrayList"), List.nil(), null)))))));

                        boolean stringItemClass = "java.lang.String".equals(itemType);
                        JCTree.JCIdent itemReaderIdent = ident(fieldItemObjectReader(i));
                        if (!stringItemClass) {
                            JCTree.JCFieldAccess getItemObjectReaderField = field(fieldReaderField, "getItemObjectReader");
                            JCTree.JCExpressionStatement getItemObjectReaderExec = exec(assign(itemReaderIdent, method(getItemObjectReaderField, List.of(jsonReaderIdent))));
                            stmts.append(defIf(binary(JCTree.Tag.EQ, itemReaderIdent, nullLiteral), block(0, List.of(getItemObjectReaderExec)), null));
                        }

                        JCTree.JCMethodInvocation nextIfArrayStartMethod = method(field(jsonReaderIdent, "nextIfArrayStart"));
                        JCTree.JCMethodInvocation nextIfArrayEndMethod = method(field(jsonReaderIdent, "nextIfArrayEnd"));
                        ListBuffer<JCTree.JCStatement> whileBody = new ListBuffer<>();
                        JCTree.JCExpression item;
                        if (stringItemClass) {
                            item = method(field(jsonReaderIdent, "readString"));
                        } else {
                            item = cast(qualIdent(itemType), method(field(itemReaderIdent, "readObject"), List.of(jsonReaderIdent, nullLiteral, nullLiteral, literal(TypeTag.LONG, 0))));
                        }
                        whileBody.append(exec(method(field(ident(fieldValueVar.name), "add"), List.of(item))));
                        JCTree.JCWhileLoop arrayWhile = whileLoop(unary(JCTree.Tag.NOT, nextIfArrayEndMethod), block(0, whileBody.toList()));
                        stmts.append(defIf(nextIfArrayStartMethod, block(0, List.of(arrayWhile)), null));
                        valueExpr = ident(fieldValueVar.name);
                        break;
                    }
                }

                JCTree.JCIdent objectReaderIdent = ident(fieldObjectReader(i));
                JCTree.JCMethodInvocation getObjectReaderMethod = method(field(fieldReaderField, "getObjectReader"), List.of(jsonReaderIdent));
                JCTree.JCAssign objectReaderAssign = assign(objectReaderIdent, getObjectReaderMethod);
                stmts.append(defIf(binary(JCTree.Tag.EQ, objectReaderIdent, nullLiteral), block(0, List.of(exec(objectReaderAssign))), null));
                JCTree.JCMethodInvocation objectMethod = method(field(field(ident(names._this), fieldObjectReader(i)), isJsonb ? "readJSONBObject" : "readObject"), List.of(jsonReaderIdent, field(fieldReaderField, "fieldType"), literal(field.name), literal(TypeTag.LONG, 0)));
                JCTree.JCTypeCast objectCast = cast(fieldValueType, objectMethod);
                stmts.append(exec(assign(ident(fieldValueVar.name), objectCast)));

                valueExpr = ident(fieldValueVar.name);
                break;
        }
        if (field.setMethod != null) {
            stmts.append(exec(method(field(objectIdent, field.setMethod.getSimpleName().toString()), List.of(valueExpr))));
        } else if (field.field != null) {
            stmts.append(exec(assign(field(objectIdent, field.field.getSimpleName().toString()), valueExpr)));
        } else {
            messager.printMessage(Diagnostic.Kind.WARNING, "not implemented yet");
        }
        stmts.append(defContinue(forLabel));

        if (isIf) {
            return List.of(defIf(binary(JCTree.Tag.EQ, literal(TypeTag.LONG, fieldNameHash), readFieldNameHashCodeMethod), block(0, stmts.toList()), null));
        } else {
            return stmts.toList();
        }
    }

    private void genSource(String classNamePath, StructInfo info, String importsStr, JCTree.JCClassDecl beanClassDecl) {
        Integer cnt = count.getOrDefault(classNamePath, -1);
        String newClassNamePath = classNamePath + "_Source" + (cnt + 1);
        count.put(classNamePath, cnt + 1);
        try {
            JavaFileObject converterFile = processingEnv.getFiler().createSourceFile(newClassNamePath, info.element);
            try (Writer writer = converterFile.openWriter()) {
                int idx = newClassNamePath.lastIndexOf(".");
                String oldPkgPath = newClassNamePath.substring(0, idx);
                String newPkgPath = oldPkgPath.toLowerCase();
                writer.write("package " + newPkgPath + ";");
                writer.write(System.lineSeparator());
                writer.write(System.lineSeparator());
                writer.write(importsStr);
                java.util.List<String> annos = beanClassDecl.mods.annotations.stream().map(a -> a.toString()).collect(Collectors.toList());
                String str = beanClassDecl.toString();
                for (String s : annos) {
                    str = str.replace(s, "");
                }
                while (str.startsWith(System.lineSeparator())) {
                    str = str.replaceFirst(System.lineSeparator(), "");
                }
                writer.write(System.lineSeparator());
                String newClassName = newClassNamePath.substring(idx + 1);
                String oldClasName = beanClassDecl.getSimpleName().toString();
                str = str.replaceAll(" static class " + oldClasName, " class " + newClassName);
                str = str.replaceAll(" " + oldClasName + " ", " " + newClassName + " ");
                str = str.replaceAll("\\." + oldClasName + " ", "." + newClassName + " ");
                str = str.replaceAll("\\." + oldClasName + "\\(", "." + newClassName + "(");
                str = str.replaceAll("\\." + oldClasName + "\\.", "." + newClassName + ".");
                str = str.replaceAll(" " + oldClasName + "\\(", " " + newClassName + "(");
                str = str.replaceAll(oldPkgPath, newPkgPath);
                writer.write(str);
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Failed saving compiled json serialization file " + newClassNamePath);
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed creating compiled json serialization file " + newClassNamePath);
        }
    }

    private JCTree.JCExpression getFieldValueType(String type) {
        int open = type.indexOf("<");
        int close = type.indexOf(">");
        if (open == -1 && close == -1) {
            return qualIdent(type);
        } else {
            String collection = type.substring(0, open);
            String generic = type.substring(open + 1, close);
            return typeApply(qualIdent(collection), List.of(qualIdent(generic)));
        }
    }
}
