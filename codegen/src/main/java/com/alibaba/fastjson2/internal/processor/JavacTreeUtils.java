package com.alibaba.fastjson2.internal.processor;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;

final class JavacTreeUtils {
    private static TreeMaker treeMaker;
    private static Names names;
    private static Elements elements;

    private JavacTreeUtils() {
        throw new UnsupportedOperationException("this class can not be instantiated");
    }

    static void initialize(TreeMaker _treeMaker, Names _names, Elements _elements) {
        treeMaker = _treeMaker;
        names = _names;
        elements = _elements;
    }

    static Name name(String name) {
        return names.fromString(name);
    }

    static JCTree.JCIdent ident(String name) {
        return treeMaker.Ident(name(name));
    }

    static JCTree.JCIdent ident(Name name) {
        return treeMaker.Ident(name);
    }

    static JCTree.JCExpression qualIdent(String name) {
        TypeElement typeElement = elements.getTypeElement(name);
        if (typeElement != null) {
            return treeMaker.QualIdent((Symbol) typeElement);
        } else {
            return ident(name(name));
        }
    }

    static JCTree.JCVariableDecl defVar(long flag, String identName, JCTree.JCExpression identType) {
        return defVar(flag, identName, identType, null);
    }

    static JCTree.JCVariableDecl defVar(long flag, String identName, JCTree.JCExpression identType, JCTree.JCExpression init) {
        return treeMaker.VarDef(modifiers(flag), name(identName), identType, init);
    }

    static JCTree.JCMethodDecl defMethod(int flag, String name, JCTree.JCExpression rtnType, List<JCTree.JCTypeParameter> typeArgs, List<JCTree.JCVariableDecl> params, List<JCTree.JCExpression> recvArgs, JCTree.JCBlock block, JCTree.JCExpression defaultValue) {
        return defMethod(flag, name(name), rtnType, typeArgs, params, recvArgs, block, defaultValue);
    }

    static JCTree.JCMethodDecl defMethod(int flag, Name name, JCTree.JCExpression rtnType, List<JCTree.JCTypeParameter> typeArgs, List<JCTree.JCVariableDecl> params, List<JCTree.JCExpression> recvArgs, JCTree.JCBlock block, JCTree.JCExpression defaultValue) {
        return treeMaker.MethodDef(modifiers(flag), name, rtnType, typeArgs, params, recvArgs, block, defaultValue);
    }

    static JCTree.JCMethodInvocation method(JCTree.JCExpression method) {
        return method(null, method, null);
    }

    static JCTree.JCMethodInvocation method(JCTree.JCExpression method, List<JCTree.JCExpression> args) {
        return method(null, method, args);
    }

    static JCTree.JCMethodInvocation method(List<JCTree.JCExpression> typeArgs, JCTree.JCExpression method, List<JCTree.JCExpression> args) {
        if (typeArgs == null) {
            typeArgs = List.nil();
        }
        if (args == null) {
            args = List.nil();
        }
        return treeMaker.Apply(typeArgs, method, args);
    }

    static JCTree.JCFieldAccess field(JCTree.JCExpression expr, String name) {
        return treeMaker.Select(expr, name(name));
    }

    static JCTree.JCFieldAccess field(JCTree.JCExpression expr, Name name) {
        return treeMaker.Select(expr, name);
    }

    static JCTree.JCModifiers modifiers(long flag) {
        return treeMaker.Modifiers(flag);
    }

    static JCTree.JCExpressionStatement exec(JCTree.JCExpression expr) {
        return treeMaker.Exec(expr);
    }

    static JCTree.JCAssign assign(JCTree.JCExpression expr1, JCTree.JCExpression expr2) {
        return treeMaker.Assign(expr1, expr2);
    }

    static JCTree.JCIf defIf(JCTree.JCExpression cond, JCTree.JCStatement thenStmt, JCTree.JCStatement elseStmt) {
        return treeMaker.If(cond, thenStmt, elseStmt);
    }

    static JCTree.JCBinary binary(JCTree.Tag tag, JCTree.JCExpression expr1, JCTree.JCExpression expr2) {
        return treeMaker.Binary(tag, expr1, expr2);
    }

    static JCTree.JCUnary unary(JCTree.Tag tag, JCTree.JCExpression expr) {
        return treeMaker.Unary(tag, expr);
    }

    static JCTree.JCBlock block(long pos, List<JCTree.JCStatement> stmts) {
        return treeMaker.Block(pos, stmts);
    }

    static JCTree.JCLiteral literal(TypeTag tag, Object object) {
        return treeMaker.Literal(tag, object);
    }

    static JCTree.JCLiteral literal(Object object) {
        return treeMaker.Literal(object);
    }

    static JCTree.JCTypeCast cast(JCTree type, JCTree.JCExpression expr) {
        return treeMaker.TypeCast(type, expr);
    }

    static JCTree.JCNewClass newClass(JCTree.JCExpression encl, List<JCTree.JCExpression> typeArgs, JCTree.JCExpression clazz, List<JCTree.JCExpression> args, JCTree.JCClassDecl def) {
        return treeMaker.NewClass(encl, typeArgs, clazz, args, def);
    }

    static JCTree.JCPrimitiveTypeTree type(TypeTag tag) {
        return treeMaker.TypeIdent(tag);
    }

    static JCTree.JCLabeledStatement label(String name, JCTree.JCStatement stmt) {
        return treeMaker.Labelled(name(name), stmt);
    }

    static JCTree.JCBreak defBreak(JCTree.JCLabeledStatement labeledStatement) {
        return defBreak(labeledStatement.label);
    }

    static JCTree.JCBreak defBreak(Name name) {
        return treeMaker.Break(name);
    }

    static JCTree.JCContinue defContinue(JCTree.JCLabeledStatement labeledStatement) {
        return defContinue(labeledStatement.label);
    }

    static JCTree.JCContinue defContinue(Name name) {
        return treeMaker.Continue(name);
    }

    static JCTree.JCForLoop forLoop(List<JCTree.JCStatement> initStmts, JCTree.JCExpression condExpr, List<JCTree.JCExpressionStatement> stepExprs, JCTree.JCStatement bodyStmt) {
        if (initStmts == null) {
            initStmts = List.nil();
        }
        if (stepExprs == null) {
            stepExprs = List.nil();
        }
        return treeMaker.ForLoop(initStmts, condExpr, stepExprs, bodyStmt);
    }

    static JCTree.JCCase defCase(JCTree.JCExpression matchExpr, List<JCTree.JCStatement> matchStmts) {
        if (matchStmts == null) {
            matchStmts = List.nil();
        }
        return treeMaker.Case(matchExpr, matchStmts);
    }

    static JCTree.JCSwitch defSwitch(JCTree.JCExpression selectorExpr, List<JCTree.JCCase> cases) {
        if (cases == null) {
            cases = List.nil();
        }
        return treeMaker.Switch(selectorExpr, cases);
    }

    static JCTree.JCReturn defReturn(JCTree.JCExpression expr) {
        return treeMaker.Return(expr);
    }

    static JCTree.JCParens parens(JCTree.JCExpression expr) {
        return treeMaker.Parens(expr);
    }

    static JCTree.JCWhileLoop whileLoop(JCTree.JCExpression condExpr, JCTree.JCStatement bodyStmt) {
        return treeMaker.WhileLoop(condExpr, bodyStmt);
    }

    static JCTree.JCTypeApply typeApply(JCTree.JCExpression clazz, List<JCTree.JCExpression> args) {
        if (args == null) {
            args = List.nil();
        }
        return treeMaker.TypeApply(clazz, args);
    }

    static JCTree.JCArrayAccess indexed(JCTree.JCExpression indexedExpr, JCTree.JCExpression indexExpr) {
        return treeMaker.Indexed(indexedExpr, indexExpr);
    }

    static JCTree.JCLambda lambda(List<JCTree.JCVariableDecl> args, JCTree body) {
        if (args == null) {
            args = List.nil();
        }
        return treeMaker.Lambda(args, body);
    }

    static JCTree.JCNewArray newArray(JCTree.JCExpression elemTypeExpr, List<JCTree.JCExpression> dimsExprs, List<JCTree.JCExpression> elemDataExprs) {
        if (dimsExprs == null) {
            dimsExprs = List.nil();
        }
        return treeMaker.NewArray(elemTypeExpr, dimsExprs, elemDataExprs);
    }

    static JCTree.JCClassDecl defClass(int flag, String name, List<JCTree.JCTypeParameter> typeArgs, JCTree.JCExpression extendExpr, List<JCTree.JCExpression> implementExprs, List<JCTree> defs) {
        if (typeArgs == null) {
            typeArgs = List.nil();
        }
        if (implementExprs == null) {
            implementExprs = List.nil();
        }
        if (defs == null) {
            defs = List.nil();
        }
        return treeMaker.ClassDef(modifiers(flag), name(name), typeArgs, extendExpr, implementExprs, defs);
    }

    static void pos(int pos) {
        treeMaker.pos = pos;
    }
}
