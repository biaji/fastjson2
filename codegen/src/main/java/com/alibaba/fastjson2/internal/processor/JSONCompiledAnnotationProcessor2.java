package com.alibaba.fastjson2.internal.processor;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.annotation.JSONCompiled;
import com.alibaba.fastjson2.reader.FieldReader;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.reader.ObjectReaderAdapter;
import com.alibaba.fastjson2.reader.ObjectReaders;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.*;

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

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.javacTrees = JavacTrees.instance(processingEnv);
        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        this.names = Names.instance(context);
        initialize(TreeMaker.instance(context), names, processingEnv.getElementUtils());
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
            StructInfo structInfo = structs.get(element.toString());
            java.util.List<AttributeInfo> attributeInfos = structInfo.getReaderAttributes();
            int fieldsSize = attributeInfos.size();
            Class superClass = getSuperClass(attributeInfos.size());

            JCTree tree = javacTrees.getTree(element);
            pos(tree.pos);
            tree.accept(new TreeTranslator() {
                @Override
                public void visitClassDef(JCTree.JCClassDecl beanClassDecl) {
                    super.visitClassDef(beanClassDecl);

                    String beanFQN = beanClassDecl.sym.toString();
                    if (element.toString().equals(beanFQN)) {
                        // initialization
                        String innerClassFQN = findConverterName(structInfo);
                        int dotIdx = innerClassFQN.lastIndexOf('.');
                        if (dotIdx == -1) {
                            messager.printMessage(Diagnostic.Kind.ERROR, String.format("not qualified inner class name for %s", beanFQN));
                        }
                        String innerClassName = innerClassFQN.substring(dotIdx + 1);
                        JCTree.JCExpression beanType = qualIdent(beanFQN);
                        JCTree.JCNewClass beanNew = newClass(null, null, beanType, null, null);
                        JCTree.JCIdent objectType = ident("Object");

                        // generate inner class
                        JCTree.JCClassDecl innerClass = genInnerClass(innerClassName, superClass);

                        // generate fields if necessary
                        final boolean generatedFields = fieldsSize < 128;
                        if (generatedFields) {
                            innerClass.defs = innerClass.defs.prependList(genFields(attributeInfos, superClass));
                        }

                        // generate constructor
                        innerClass.defs = innerClass.defs.append(genConstructor(beanType, beanNew, attributeInfos, superClass, generatedFields));

                        // generate createInstance
                        innerClass.defs = innerClass.defs.append(genCreateInstance(objectType, beanNew));

                        // generate readObject
                        innerClass.defs = innerClass.defs.append(genReadObject(objectType, beanFQN, beanNew, attributeInfos, structInfo, false));

                        // link with inner class
                        beanClassDecl.defs = beanClassDecl.defs.append(innerClass);

                        // generate source file
                        genSource(innerClassFQN, structInfo, innerClass);
                    }
                }
            });
        });
        return true;
    }

    private List<JCTree> genFields(java.util.List<AttributeInfo> attributeInfos, Class superClass) {
        ListBuffer<JCTree> stmts = new ListBuffer<>();
        int fieldsSize = attributeInfos.size();
        JCTree.JCExpression fieldReaderType = qualIdent(FieldReader.class.getName());
        JCTree.JCExpression objectReaderType = qualIdent(ObjectReader.class.getName());
        if (superClass == ObjectReaderAdapter.class) {
            for (int i = 0; i < fieldsSize; ++i) {
                stmts.append(defVar(Flags.PRIVATE, fieldReader(i), fieldReaderType));
            }

            for (int i = 0; i < fieldsSize; i++) {
                stmts.append(defVar(Flags.PRIVATE, fieldObjectReader(i), objectReaderType));
            }
        }

        for (int i = 0; i < fieldsSize; ++i) {
            AttributeInfo field = attributeInfos.get(i);
            String fieldType = field.type.toString();
            if (fieldType.startsWith("java.util.List<") || fieldType.startsWith("java.util.Map<java.lang.String,")) {
                stmts.append(defVar(Flags.PRIVATE, fieldItemObjectReader(i), objectReaderType));
            }
        }
        return stmts.toList();
    }

    private JCTree.JCClassDecl genInnerClass(String className, Class superClass) {
        JCTree.JCClassDecl innerClass = defClass(Flags.PRIVATE | Flags.STATIC,
                className,
                null,
                qualIdent(superClass.getName()),
                null,
                null);
        return innerClass;
    }

    private JCTree.JCMethodDecl genConstructor(JCTree.JCExpression beanType, JCTree.JCNewClass beanNew, java.util.List<AttributeInfo> fields, Class superClass, boolean generatedFields) {
        JCTree.JCLambda lambda = lambda(List.nil(), beanNew);
        JCTree.JCExpression fieldReaderType = qualIdent(FieldReader.class.getName());
        ListBuffer fieldReaders = new ListBuffer<>();
        JCTree.JCExpression objectReadersType = qualIdent(ObjectReaders.class.getName());
        int fieldsSize = fields.size();
        for (int i = 0; i < fieldsSize; ++i) {
            JCTree.JCMethodInvocation readerMethod = null;
            AttributeInfo attributeInfo = fields.get(i);
            if (attributeInfo.setMethod != null) {
                String methodName = attributeInfo.setMethod.getSimpleName().toString();
                readerMethod = method(field(objectReadersType, "fieldReaderWithMethod"), List.of(literal(attributeInfo.name), field(beanType, names._class), literal(methodName)));
            } else if (attributeInfo.field != null) {
                String fieldName = attributeInfo.field.getSimpleName().toString();
                if (fieldName.equals(attributeInfo.name)) {
                    readerMethod = method(field(objectReadersType, "fieldReaderWithField"), List.of(literal(fieldName), field(beanType, names._class)));
                } else {
                    readerMethod = method(field(objectReadersType, "fieldReaderWithField"), List.of(literal(attributeInfo.name), field(beanType, names._class), literal(fieldName)));
                }
            } else {
                messager.printMessage(Diagnostic.Kind.WARNING, "not implemented yet");
            }
            if (readerMethod != null) {
                fieldReaders.append(readerMethod);
            }
        }
        JCTree.JCNewArray fieldReadersArray = newArray(fieldReaderType, null, List.from(fieldReaders));
        JCTree.JCMethodInvocation superMethod = method(ident(names._super), List.of(field(beanType, names._class), defNull(), defNull(), literal(TypeTag.LONG, 0), defNull(), lambda, defNull(), fieldReadersArray));
        ListBuffer<JCTree.JCStatement> stmts = new ListBuffer<>();
        stmts.append(exec(superMethod));
        // initialize fields if necessary
        if (superClass == ObjectReaderAdapter.class && generatedFields) {
            stmts.appendList(genInitFields(fieldsSize));
        }
        return defMethod(Flags.PUBLIC, names.init, type(TypeTag.VOID), null, null, null, block(stmts.toList()), null);
    }

    private List<JCTree.JCStatement> genInitFields(int fieldsSize) {
        ListBuffer<JCTree.JCStatement> stmts = new ListBuffer<>();
        for (int i = 0; i < fieldsSize; ++i) {
            stmts.append(exec(assign(ident(fieldReader(i)), indexed(field(ident(names._this), "fieldReaders"), literal(i)))));
        }
        return stmts.toList();
    }

    private JCTree.JCMethodDecl genCreateInstance(JCTree.JCIdent objectType, JCTree.JCNewClass beanNew) {
        JCTree.JCVariableDecl featuresVar = defVar(Flags.PARAMETER, "features", type(TypeTag.LONG));
        return defMethod(Flags.PUBLIC, "createInstance", objectType, null, List.of(featuresVar), null, block(defReturn(beanNew)), null);
    }

    private JCTree.JCMethodDecl genReadObject(JCTree.JCIdent objectType, String classNamePath, JCTree.JCNewClass beanNew, java.util.List<AttributeInfo> attributeInfos, StructInfo structInfo, boolean isJsonb) {
        JCTree.JCVariableDecl jsonReaderVar = defVar(Flags.PARAMETER, "jsonReader", qualIdent(JSONReader.class.getName()));
        JCTree.JCIdent jsonReaderIdent = ident(jsonReaderVar.name);
        JCTree.JCVariableDecl fieldTypeVar = defVar(Flags.PARAMETER, "fieldType", qualIdent(Type.class.getName()));
        JCTree.JCVariableDecl fieldNameVar = defVar(Flags.PARAMETER, "fieldName", objectType);
        JCTree.JCVariableDecl featuresVar = defVar(Flags.PARAMETER, "features", type(TypeTag.LONG));

        JCTree.JCReturn nullReturn = defReturn(defNull());

        ListBuffer<JCTree.JCStatement> readObjectBody = new ListBuffer<>();

        JCTree.JCMethodInvocation nextIfNullMethod = method(field(jsonReaderIdent, "nextIfNull"));
        readObjectBody.append(defIf(nextIfNullMethod, block(nullReturn), null));

        readObjectBody.append(exec(method(field(jsonReaderIdent, "nextIfObjectStart"))));

        JCTree.JCVariableDecl features2Var = defVar(Flags.PARAMETER, "features2", type(TypeTag.LONG), binary(JCTree.Tag.BITOR, ident("features"), field(ident(names._this), "features")));
        readObjectBody.append(features2Var);

        JCTree.JCVariableDecl objectVar = defVar(Flags.PARAMETER, "object", qualIdent(classNamePath), beanNew);
        JCTree.JCIdent objectIdent = ident(objectVar.name);
        readObjectBody.append(objectVar);

        int fieldsSize = attributeInfos.size();

        JCTree.JCLabeledStatement loopLabel = label("_loop", null);
        JCTree.JCWhileLoop loopHead = whileLoop(unary(JCTree.Tag.NOT, method(field(jsonReaderIdent, "nextIfObjectEnd"))), null);
        ListBuffer<JCTree.JCStatement> loopBody = new ListBuffer<>();
        JCTree.JCFieldAccess readFieldNameHashCode = field(jsonReaderIdent, "readFieldNameHashCode");
        JCTree.JCVariableDecl hashCode64Var = defVar(Flags.PARAMETER, "hashCode64", type(TypeTag.LONG), cast(type(TypeTag.LONG), method(readFieldNameHashCode)));
        JCTree.JCExpression hashCode64 = ident(hashCode64Var.name);
        loopBody.append(hashCode64Var);
        if (fieldsSize <= 6) {
            for (int i = 0; i < fieldsSize; ++i) {
                AttributeInfo attributeInfo = attributeInfos.get(i);
                loopBody.appendList(genReadFieldValue(attributeInfo, jsonReaderIdent, i, structInfo, attributeInfo.nameHashCode, hashCode64, loopLabel, objectIdent, true, isJsonb));
            }
        } else {
            Map<Integer, java.util.List<Long>> map = new TreeMap();
            Map<Long, AttributeInfo> mapping = new TreeMap();
            Map<Long, Integer> mappingIndex = new TreeMap();
            for (int i = 0; i < fieldsSize; ++i) {
                AttributeInfo attr = attributeInfos.get(i);
                long fieldNameHash = attr.nameHashCode;
                int hashCode32 = (int) (fieldNameHash ^ (fieldNameHash >>> 32));
                java.util.List<Long> hashCode64List = map.computeIfAbsent(hashCode32, k -> new ArrayList<>());
                hashCode64List.add(fieldNameHash);
                mapping.put(fieldNameHash, attr);
                mappingIndex.put(fieldNameHash, i);
            }
            int[] hashCode32Keys = new int[map.size()];
            int off = 0;
            for (Integer key : map.keySet()) {
                hashCode32Keys[off++] = key;
            }
            Arrays.sort(hashCode32Keys);
            JCTree.JCVariableDecl hashCode32Var = getHashCode32Var(hashCode64);
            loopBody.append(hashCode32Var);
            JCTree.JCExpression hashCode32 = ident(hashCode32Var.name);
            ListBuffer<JCTree.JCCase> cases = new ListBuffer<>();
            for (int i = 0; i < fieldsSize; ++i) {
                JCTree.JCLabeledStatement iLabel = label(i + "", null);
                java.util.List<Long> hashCode64Array = map.get(hashCode32Keys[i]);
                List<JCTree.JCStatement> stmts = List.nil();
                Long fieldNameHash = null;
                if (hashCode64Array.size() == 1 && hashCode64Array.get(0) == hashCode32Keys[i]) {
                    fieldNameHash = hashCode64Array.get(0);
                    int index = mappingIndex.get(fieldNameHash);
                    AttributeInfo attributeInfo = mapping.get(fieldNameHash);
                    stmts = stmts.appendList(genReadFieldValue(attributeInfo, jsonReaderIdent, index, structInfo, attributeInfo.nameHashCode, hashCode64, loopLabel, objectIdent, false, isJsonb));
                    stmts.append(defContinue(loopLabel));
                } else {
                    for (int j = 0; j < hashCode64Array.size(); ++j) {
                        fieldNameHash = hashCode64Array.get(j);
                        int index = mappingIndex.get(fieldNameHash);
                        AttributeInfo field = mapping.get(fieldNameHash);
                        List<JCTree.JCStatement> stmtsIf = genReadFieldValue(field, jsonReaderIdent, index, structInfo, field.nameHashCode, hashCode64, loopLabel, objectIdent, false, isJsonb);
                        stmts = stmts.append(defIf(binary(JCTree.Tag.EQ, hashCode64, literal(TypeTag.LONG, fieldNameHash)), block(stmtsIf), null));
                        stmts.append(defContinue(loopLabel));
                    }
                    stmts.append(defBreak(iLabel));
                }
                cases.append(defCase(getHashCode32Var(literal(TypeTag.LONG, fieldNameHash)).getInitializer(), stmts));
            }
            loopBody.append(defSwitch(hashCode32, cases.toList()));
        }
        if (structInfo.smartMatch) {
            loopBody.append(defIf(method(field(ident(names._this), "readFieldValueWithLCase"), List.of(jsonReaderIdent, objectIdent, ident(hashCode64Var.name), ident(features2Var.name))), block(defContinue(loopLabel)), null));
        }
        JCTree.JCFieldAccess processExtraField = field(ident(names._this), "processExtra");
        loopBody.append(exec(method(processExtraField, List.of(jsonReaderIdent, objectIdent))));
        loopHead.body = block(loopBody.toList());
        loopLabel.body = loopHead;
        readObjectBody.append(loopLabel);

        readObjectBody.append(exec(method(field(jsonReaderIdent, "nextIfComma"))));

        readObjectBody.append(defReturn(objectIdent));

        return defMethod(Flags.PUBLIC, "readObject", objectType, null, List.of(jsonReaderVar, fieldTypeVar, fieldNameVar, featuresVar), null, block(readObjectBody.toList()), null);
    }

    private JCTree.JCVariableDecl getHashCode32Var(JCTree.JCExpression hashCode64) {
        JCTree.JCBinary usrBinary = binary(JCTree.Tag.USR, hashCode64, literal(TypeTag.INT, 32));
        JCTree.JCPrimitiveTypeTree intType = type(TypeTag.INT);
        JCTree.JCTypeCast hashCode32Cast = cast(intType, binary(JCTree.Tag.BITXOR, hashCode64, parens(usrBinary)));
        return defVar(Flags.PARAMETER, "hashCode32", intType, hashCode32Cast);
    }

    private List<JCTree.JCStatement> genReadFieldValue(
            AttributeInfo attributeInfo,
            JCTree.JCIdent jsonReaderIdent,
            int i,
            StructInfo structInfo,
            long fieldNameHash,
            JCTree.JCExpression readFieldNameHashCodeMethod,
            JCTree.JCLabeledStatement loopLabel,
            JCTree.JCIdent objectIdent,
            boolean isIf,
            boolean isJsonb) {
        JCTree.JCExpression valueExpr = null;
        ListBuffer<JCTree.JCStatement> stmts = new ListBuffer<>();
        String type = attributeInfo.type.toString();

        boolean referenceDetect = structInfo.referenceDetect;
        if (referenceDetect) {
            referenceDetect = isReference(type);
        }

        JCTree.JCFieldAccess fieldReaderField = field(ident(names._this), fieldReader(i));
        if (referenceDetect) {
            ListBuffer<JCTree.JCStatement> thenStmts = new ListBuffer<>();
            JCTree.JCMethodInvocation readReferenceMethod = method(field(jsonReaderIdent, "readReference"));
            JCTree.JCVariableDecl refVar = defVar(Flags.PARAMETER, "ref", ident("String"), readReferenceMethod);
            thenStmts.append(refVar);
            JCTree.JCMethodInvocation addResolveTaskMethod = method(field(fieldReaderField, "addResolveTask"), List.of(jsonReaderIdent, objectIdent, ident(refVar.name)));
            thenStmts.append(exec(addResolveTaskMethod));
            thenStmts.append(defContinue(loopLabel));
            stmts.append(defIf(method(field(jsonReaderIdent, "isReference")), block(thenStmts.toList()), null));
        }

        String readDirectMethod = getReadDirectMethod(type);
        if (readDirectMethod != null) {
            valueExpr = method(field(jsonReaderIdent, readDirectMethod));
        } else {
            JCTree.JCExpression fieldValueType = getFieldValueType(type);
            JCTree.JCVariableDecl fieldValueVar = defVar(Flags.PARAMETER, attributeInfo.name, fieldValueType);
            stmts.append(fieldValueVar);

            if (type.startsWith("java.util.List<")) {
                valueExpr = genFieldValueList(type, attributeInfo, jsonReaderIdent, fieldValueVar, loopLabel, stmts, i, referenceDetect, fieldReaderField);
            } else if (type.startsWith("java.util.Map<java.lang.String,")) {
                valueExpr = genFieldValueMap(type, attributeInfo, jsonReaderIdent, fieldValueVar, loopLabel, stmts, i, referenceDetect);
            }

            if (valueExpr == null) {
                JCTree.JCIdent objectReaderIdent = ident(fieldObjectReader(i));
                JCTree.JCMethodInvocation getObjectReaderMethod = method(field(fieldReaderField, "getObjectReader"), List.of(jsonReaderIdent));
                JCTree.JCAssign objectReaderAssign = assign(objectReaderIdent, getObjectReaderMethod);
                stmts.append(defIf(binary(JCTree.Tag.EQ, objectReaderIdent, defNull()), block(exec(objectReaderAssign)), null));
                JCTree.JCMethodInvocation objectMethod = method(field(field(ident(names._this), fieldObjectReader(i)), isJsonb ? "readJSONBObject" : "readObject"), List.of(jsonReaderIdent, field(fieldReaderField, "fieldType"), literal(attributeInfo.name), literal(TypeTag.LONG, 0)));
                stmts.append(exec(assign(ident(fieldValueVar.name), cast(fieldValueType, objectMethod))));
                valueExpr = ident(fieldValueVar.name);
            }
        }

        if (attributeInfo.setMethod != null) {
            stmts.append(exec(method(field(objectIdent, attributeInfo.setMethod.getSimpleName().toString()), List.of(valueExpr))));
        } else if (attributeInfo.field != null) {
            stmts.append(exec(assign(field(objectIdent, attributeInfo.field.getSimpleName().toString()), valueExpr)));
        } else {
            messager.printMessage(Diagnostic.Kind.WARNING, "not implemented yet");
        }
        stmts.append(defContinue(loopLabel));

        if (isIf) {
            return List.of(defIf(binary(JCTree.Tag.EQ, readFieldNameHashCodeMethod, literal(TypeTag.LONG, fieldNameHash)), block(stmts.toList()), null));
        } else {
            return stmts.toList();
        }
    }

    private JCTree.JCExpression genFieldValueList(String type,
                                                  AttributeInfo attributeInfo,
                                                  JCTree.JCIdent jsonReaderIdent,
                                                  JCTree.JCVariableDecl fieldValueVar,
                                                  JCTree.JCLabeledStatement loopLabel,
                                                  ListBuffer<JCTree.JCStatement> stmts,
                                                  int i,
                                                  boolean referenceDetect,
                                                  JCTree.JCFieldAccess fieldReaderField) {
        String itemType = type.substring(15, type.length() - 1);
        boolean itemTypeIsClass = itemType.indexOf('<') == -1;
        if (itemTypeIsClass) {
            JCTree.JCMethodInvocation nextIfNullMethod = method(field(jsonReaderIdent, "nextIfNull"));
            stmts.append(defIf(nextIfNullMethod,
                    block(exec(assign(ident(fieldValueVar.name), defNull()))),
                    block(exec(assign(ident(fieldValueVar.name), newClass(null, null, qualIdent("java.util.ArrayList"), null, null))))));

            String readDirectMethod = getReadDirectMethod(itemType);
            JCTree.JCIdent itemReaderIdent = ident(fieldItemObjectReader(i));
            if (readDirectMethod == null) {
                JCTree.JCFieldAccess getItemObjectReaderField = field(fieldReaderField, "getItemObjectReader");
                JCTree.JCExpressionStatement getItemObjectReaderExec = exec(assign(itemReaderIdent, method(getItemObjectReaderField, List.of(jsonReaderIdent))));
                stmts.append(defIf(binary(JCTree.Tag.EQ, itemReaderIdent, defNull()), block(getItemObjectReaderExec), null));
            }

            if (referenceDetect) {
                referenceDetect = isReference(itemType);
            }

            JCTree.JCVariableDecl for_iVar;
            if ("i".equals(attributeInfo.name)) {
                for_iVar = defVar(Flags.PARAMETER, "j", type(TypeTag.INT), literal(0));
            } else {
                for_iVar = defVar(Flags.PARAMETER, "i", type(TypeTag.INT), literal(0));
            }
            JCTree.JCMethodInvocation nextIfArrayStartMethod = method(field(jsonReaderIdent, "nextIfArrayStart"));
            JCTree.JCMethodInvocation nextIfArrayEndMethod = method(field(jsonReaderIdent, "nextIfArrayEnd"));
            ListBuffer<JCTree.JCStatement> whileStmts = new ListBuffer<>();
            JCTree.JCExpression item;
            if (readDirectMethod != null) {
                item = method(field(jsonReaderIdent, readDirectMethod));
            } else {
                item = cast(qualIdent(itemType), method(field(itemReaderIdent, "readObject"), List.of(jsonReaderIdent, defNull(), defNull(), literal(TypeTag.LONG, 0))));
            }

            if (referenceDetect) {
                JCTree.JCVariableDecl listItemVar = defVar(Flags.PARAMETER, attributeInfo.name + "_item", getFieldValueType(itemType), item);
                ListBuffer<JCTree.JCStatement> isReferenceStmts = new ListBuffer<>();
                JCTree.JCMethodInvocation readReferenceMethod = method(field(jsonReaderIdent, "readReference"));
                JCTree.JCVariableDecl refVar = defVar(Flags.PARAMETER, "ref", ident("String"), readReferenceMethod);
                isReferenceStmts.append(refVar);
                JCTree.JCMethodInvocation addResolveTaskMethod = method(field(jsonReaderIdent, "addResolveTask"), List.of(ident(fieldValueVar.name), ident(for_iVar.name), method(field(qualIdent("com.alibaba.fastjson2.JSONPath"), "of"), List.of(ident(refVar.name)))));
                isReferenceStmts.append(exec(addResolveTaskMethod));
                isReferenceStmts.append(exec(method(field(ident(fieldValueVar.name), "add"), List.of(defNull()))));
                isReferenceStmts.append(defContinue(loopLabel));
                whileStmts.append(defIf(method(field(jsonReaderIdent, "isReference")), block(isReferenceStmts.toList()), null));
                whileStmts.append(listItemVar);
                item = ident(listItemVar.name);
            }

            whileStmts.append(exec(method(field(ident(fieldValueVar.name), "add"), List.of(item))));

            ListBuffer<JCTree.JCStatement> condStmts = new ListBuffer<>();
            if (referenceDetect) {
                condStmts.append(forLoop(List.of(for_iVar), unary(JCTree.Tag.NOT, nextIfArrayEndMethod), List.of(exec(unary(JCTree.Tag.PREINC, ident(for_iVar.name)))), block(whileStmts.toList())));
            } else {
                condStmts.append(whileLoop(unary(JCTree.Tag.NOT, nextIfArrayEndMethod), block(whileStmts.toList())));
            }

            stmts.append(defIf(nextIfArrayStartMethod, block(condStmts.toList()), null));
            return ident(fieldValueVar.name);
        }
        return null;
    }

    private JCTree.JCExpression genFieldValueMap(String type,
                                                 AttributeInfo attributeInfo,
                                                 JCTree.JCIdent jsonReaderIdent,
                                                 JCTree.JCVariableDecl fieldValueVar,
                                                 JCTree.JCLabeledStatement loopLabel,
                                                 ListBuffer<JCTree.JCStatement> stmts,
                                                 int i,
                                                 boolean referenceDetect) {
        String itemType = type.substring(31, type.length() - 1);
        boolean itemTypeIsClass = itemType.indexOf('<') == -1;
        JCTree.JCMethodInvocation nextIfNullMethod = method(field(jsonReaderIdent, "nextIfNull"));

        boolean readDirect = supportReadDirect(itemType);

        ListBuffer<JCTree.JCStatement> elseStmts = new ListBuffer<>();
        JCTree.JCIdent itemReaderIdent = ident(fieldItemObjectReader(i));
        if (!readDirect) {
            JCTree.JCFieldAccess getObjectReaderField = field(jsonReaderIdent, "getObjectReader");
            JCTree.JCExpressionStatement getItemObjectReaderExec = exec(assign(itemReaderIdent, method(getObjectReaderField, List.of(field(qualIdent(itemType), names._class)))));
            elseStmts.append(defIf(binary(JCTree.Tag.EQ, itemReaderIdent, defNull()), block(getItemObjectReaderExec), null));
        }

        elseStmts.append(exec(assign(ident(fieldValueVar.name), newClass(null, null, qualIdent("java.util.HashMap"), null, null))));

        JCTree.JCMethodInvocation nextIfObjectStartMethod = method(field(jsonReaderIdent, "nextIfObjectStart"));
        elseStmts.append(exec(nextIfObjectStartMethod));

        JCTree.JCMethodInvocation nextIfObjectEndMethod = method(field(jsonReaderIdent, "nextIfObjectEnd"));
        ListBuffer<JCTree.JCStatement> whileStmts = new ListBuffer<>();

        if (referenceDetect) {
            referenceDetect = isReference(itemType);
        }

        JCTree.JCExpression mapEntryValueExpr;
        if (readDirect) {
            mapEntryValueExpr = method(field(jsonReaderIdent, getReadDirectMethod(itemType)));
        } else {
            mapEntryValueExpr = cast(qualIdent(itemType), method(field(itemReaderIdent, "readObject"), List.of(jsonReaderIdent, field(qualIdent(itemType), names._class), literal(attributeInfo.name), ident("features"))));
        }

        JCTree.JCExpression mapEntryKeyExpr = method(field(jsonReaderIdent, "readFieldName"));

        if (referenceDetect) {
            JCTree.JCVariableDecl mapKey = defVar(Flags.PARAMETER, attributeInfo.name + "_key", ident("String"), mapEntryKeyExpr);
            whileStmts.append(mapKey);
            JCTree.JCVariableDecl mapValue = defVar(Flags.PARAMETER, attributeInfo.name + "_value", ident("String"));
            whileStmts.append(mapValue);

            ListBuffer<JCTree.JCStatement> isReferenceStmts = new ListBuffer<>();
            JCTree.JCMethodInvocation readReferenceMethod = method(field(jsonReaderIdent, "readReference"));
            JCTree.JCVariableDecl refVar = defVar(Flags.PARAMETER, "ref", ident("String"), readReferenceMethod);
            isReferenceStmts.append(refVar);
            JCTree.JCMethodInvocation addResolveTaskMethod = method(field(jsonReaderIdent, "addResolveTask"), List.of(ident(fieldValueVar.name), ident(mapKey.name), method(field(qualIdent("com.alibaba.fastjson2.JSONPath"), "of"), List.of(ident(refVar.name)))));
            isReferenceStmts.append(exec(addResolveTaskMethod));
            isReferenceStmts.append(defContinue(loopLabel));
            whileStmts.append(defIf(method(field(jsonReaderIdent, "isReference")), block(isReferenceStmts.toList()), null));
        }
        whileStmts.append(exec(method(field(ident(fieldValueVar.name), "put"), List.of(mapEntryKeyExpr, mapEntryValueExpr))));

        elseStmts.append(whileLoop(unary(JCTree.Tag.NOT, nextIfObjectEndMethod), block(whileStmts.toList())));

        elseStmts.append(exec(method(field(jsonReaderIdent, "nextIfComma"))));

        stmts.append(defIf(nextIfNullMethod,
                block(exec(assign(ident(fieldValueVar.name), defNull()))),
                block(elseStmts.toList())));

        return ident(fieldValueVar.name);
    }

    private void genSource(String fullQualifiedName, StructInfo structInfo, JCTree.JCClassDecl innerClass) {
        try {
            JavaFileObject converterFile = processingEnv.getFiler().createSourceFile(fullQualifiedName, structInfo.element);
            try (Writer writer = converterFile.openWriter()) {
                int idx = fullQualifiedName.lastIndexOf(".");
                String pkgPath = fullQualifiedName.substring(0, idx);
                writer.write("package " + pkgPath + ";");
                writer.write(System.lineSeparator());
                String str = innerClass.toString().replaceFirst("private static class ", "public class ");
                writer.write(str);
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Failed saving compiled json serialization file " + fullQualifiedName);
            }
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed creating compiled json serialization file " + fullQualifiedName);
        }
    }

    private JCTree.JCExpression getFieldValueType(String type) {
        if (type.indexOf("[") != -1) {
            return arrayIdent(type);
        }
        if (type.indexOf("<") != -1) {
            return collectionIdent(type);
        }
        return qualIdent(type);
    }

    private String findConverterName(StructInfo structInfo) {
        int dotIndex = structInfo.binaryName.lastIndexOf('.');
        String className = structInfo.binaryName.substring(dotIndex + 1);
        if (dotIndex == -1) {
            return className + "_FASTJOSNReader2";
        }
        String packageName = structInfo.binaryName.substring(0, dotIndex);
        return packageName + '.' + className + "_FASTJOSNReader2";
    }
}
