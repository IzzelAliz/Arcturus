package io.izzel.arcturus.processor;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import io.izzel.tools.Either;
import io.izzel.tools.func.Func1;
import io.izzel.tools.func.Func2;

import java.util.function.Function;

public class TailCallTransform extends TreeScanner {

    private final ArcturusTypes arcturusTypes;
    private final Log log;
    private final Symtab symtab;
    private final Names names;
    private final TreeMaker make;

    public TailCallTransform(Context context) {
        this.arcturusTypes = ArcturusTypes.instance(context);
        this.log = Log.instance(context);
        this.symtab = Symtab.instance(context);
        this.names = Names.instance(context);
        this.make = TreeMaker.instance(context);
        this.label = arcturusTypes.nameOf("$$arcturus_tailrec");
    }

    private void error(JCTree tree, String reason) {
        log.rawError(tree.pos, reason);
    }

    private JCTree.JCMethodDecl decl;
    private int success = 0;
    private List<Boolean> tail = List.nil();

    private void push(boolean b, Runnable r) {
        if (decl != null) {
            tail = tail.prepend(b);
            r.run();
            pop();
        } else {
            r.run();
        }
    }

    private void push(boolean b) {
        tail = tail.prepend(b);
    }

    private void pop() {
        tail = tail.tail;
    }

    private boolean maybeTail() {
        return decl != null && tail.head != null && tail.head;
    }

    private final Name label;
    private JCTree.JCStatement labelTarget;
    // private Symbol.VarSymbol flag;
    private JCTree.JCStatement translate = null;

    @Override
    public void visitMethodDef(JCTree.JCMethodDecl tree) {
        if (tree.sym.getAnnotationMirrors().stream().noneMatch(it -> it.type.tsym == arcturusTypes.tailrec.tsym)) {
            super.visitMethodDef(tree);
            return;
        }
        var oldSuccess = success;
        success = 0;
        var old = decl;
        decl = tree;
        scanMethodBody(tree);
        decl = old;
        if (success == 0) {
            error(tree, "tailrec method contains no recursive call");
        }
        success = oldSuccess;
    }

    private void scanMethodBody(JCTree.JCMethodDecl tree) {
        if (!tree.sym.isStatic() && !(tree.sym.isFinal() || tree.sym.isPrivate())) {
            error(tree, "tailrec method must be final or private");
            return;
        }
        // void methods have a tail call
        push(tree.sym.getReturnType().tsym == symtab.voidType.tsym, () -> {
            // $1 => { label: for (;;) $1 }
            make.at(tree.body);
            labelTarget = make.ForLoop(List.nil(), null, List.nil(), tree.body);
            scan(tree.body);
            List<JCTree.JCStatement> list = List.of(make.Labelled(label, labelTarget));
            tree.body = make.Block(0, list);
        });
    }

    private JCTree.JCStatement maybeTransTail(JCTree.JCStatement statement) {
        scan(statement);
        try {
            return translate != null ? translate : statement;
        } finally {
            translate = null;
        }
    }

    private Either<JCTree.JCExpression, JCTree.JCStatement> maybeUnliftTail(JCTree.JCExpression expression) {
        scan(expression);
        try {
            return translate != null ? Either.right(translate) : Either.left(expression);
        } finally {
            translate = null;
        }
    }

    private void translate(Either<JCTree.JCExpression, JCTree.JCStatement> p, Func1<JCTree.JCStatement, JCTree.JCStatement> f) {
        if (p.isRight()) {
            this.translate = f.apply(lift(p));
        }
    }

    private void translate(Either<JCTree.JCExpression, JCTree.JCStatement> p1, Either<JCTree.JCExpression, JCTree.JCStatement> p2,
                           Func2<JCTree.JCStatement, JCTree.JCStatement, JCTree.JCStatement> f) {
        if (p1.isRight() || p2.isRight()) {
            this.translate = f.apply(lift(p1), lift(p2));
        }
    }

    private JCTree.JCStatement lift(Either<JCTree.JCExpression, JCTree.JCStatement> p) {
        return p.fold(this::lift, Function.identity());
    }

    private JCTree.JCStatement lift(JCTree.JCExpression expression) {
        if (switchExpression != null) {
            var result = make.at(expression).Yield(expression);
            result.target = switchExpression;
            return result;
        } else {
            return make.at(expression).Return(expression);
        }
    }

    @Override
    public void visitDoLoop(JCTree.JCDoWhileLoop tree) {
        push(false, () -> super.visitDoLoop(tree));
    }

    @Override
    public void visitWhileLoop(JCTree.JCWhileLoop tree) {
        push(false, () -> super.visitWhileLoop(tree));
    }

    @Override
    public void visitForLoop(JCTree.JCForLoop tree) {
        push(false, () -> super.visitForLoop(tree));
    }

    @Override
    public void visitForeachLoop(JCTree.JCEnhancedForLoop tree) {
        push(false, () -> super.visitForeachLoop(tree));
    }

    @Override
    public void visitBlock(JCTree.JCBlock tree) {
        if (decl == null) return;
        var maybeTail = maybeTail();
        var hasTail = maybeTail; // void return
        var list = tree.stats.reverse();
        while (!list.isEmpty() && (
            // statement before void return is tail
            list.head.getKind() == Tree.Kind.RETURN && ((JCTree.JCReturn) list.head).expr == null
                // statement before break in switch before void return is tail
                || breakMaybeTail && list.head.getKind() == Tree.Kind.BREAK && ((JCTree.JCBreak) list.head).label == null
        )) {
            list = list.tail;
            hasTail = true;
        }
        if (list.isEmpty()) return;
        if (!maybeTail && hasTail) {
            push(true);
        }
        var call = maybeTransTail(list.head);
        if (!maybeTail && hasTail) {
            pop();
        }
        push(false);
        scan(list.tail);
        pop();
        if (call != list.head) {
            var head = list.head;
            tree.stats = tree.stats.map(it -> it == head ? call : it);
        }
    }

    private boolean breakMaybeTail = false;

    @Override
    public void visitSwitch(JCTree.JCSwitch tree) {
        push(false, () -> scan(tree.selector));
        var old = breakMaybeTail;
        breakMaybeTail = maybeTail();
        scan(tree.cases);
        breakMaybeTail = old;
    }

    @Override
    public void visitCase(JCTree.JCCase tree) {
        push(false, () -> scan(tree.labels));
        // pass through
        if (tree.caseKind == CaseTree.CaseKind.STATEMENT) {
            push(false);
        }
        var block = make.Block(0, tree.stats);
        scan(block);
        tree.stats = block.stats;
        if (tree.caseKind == CaseTree.CaseKind.STATEMENT) {
            pop();
        }
    }

    @Override
    public void visitSwitchExpression(JCTree.JCSwitchExpression tree) {
        push(false, () -> scan(tree.selector));
        // jump out (return/continue) from switch expression is not possible

        // return switch (_) { case _ -> yield $1; }; =>
        // {var result = switch (_) { case _ -> {
        //   // write to method params
        //   flag = true;
        //   yield zero of type($1);
        // };
        // if (flag) {
        //   flag = false;
        //   continue label;
        // } else return result;}
        var old = switchExpression;
        switchExpression = maybeTail() ? tree : null;
        scan(tree.cases);
        switchExpression = old;
    }

    private JCTree.JCSwitchExpression switchExpression;

    @Override
    public void visitYield(JCTree.JCYield tree) {
        push(switchExpression != null, () -> translate(maybeUnliftTail(tree.value), s -> s));
    }

    @Override
    public void visitSynchronized(JCTree.JCSynchronized tree) {
        if (decl != null) {
            error(tree, "synchronized block in tailrec method");
        }
    }

    @Override
    public void visitTry(JCTree.JCTry tree) {
        var hasFinally = (tree.finalizer != null && !tree.finalizer.stats.isEmpty()) || !tree.resources.isEmpty();
        // nested try blocks are not tail
        var old = decl;
        decl = null;
        scan(tree.resources);
        scan(tree.body);
        if (hasFinally) {
            scan(tree.catchers);
        }
        scan(tree.finalizer);
        decl = old;
        // when finalizers are not present, catch blocks can be tail
        if (!hasFinally) {
            scan(tree.catchers);
        }
    }

    @Override
    public void visitCatch(JCTree.JCCatch tree) {
        super.visitCatch(tree);
    }

    @Override
    public void visitConditional(JCTree.JCConditional tree) {
        push(false, () -> scan(tree.cond));
        // $0 ? $1 : $2 => if ($0) { $1 } else { $2 }
        translate(
            maybeUnliftTail(tree.truepart),
            maybeUnliftTail(tree.falsepart),
            (t, f) -> make.at(tree).If(tree.cond, t, f)
        );
    }

    @Override
    public void visitIf(JCTree.JCIf tree) {
        push(false, () -> scan(tree.cond));
        tree.thenpart = maybeTransTail(tree.thenpart);
        tree.elsepart = maybeTransTail(tree.elsepart);
    }

    @Override
    public void visitReturn(JCTree.JCReturn tree) {
        push(true,
            // { return $1 } => { $1 }
            () -> translate(maybeUnliftTail(tree.expr), s -> s)
        );
    }

    @Override
    public void visitThrow(JCTree.JCThrow tree) {
        push(false, () -> super.visitThrow(tree));
    }

    @Override
    public void visitAssert(JCTree.JCAssert tree) {
        push(false, () -> super.visitAssert(tree));
    }

    @Override
    public void visitApply(JCTree.JCMethodInvocation tree) {
        if (maybeTail()) {
            if (tree.meth instanceof JCTree.JCFieldAccess select && select.sym.baseSymbol() == decl.sym) {
                if (select.selected instanceof JCTree.JCIdent ident) {
                    if (ident.name == names._super) {
                        error(ident, "tailrec method targeting super");
                    } else if (ident.name == names._this) {
                        this.translate = makeTailCall(tree);
                    } else {
                        error(select.selected, "tailrec method changing 'this'");
                    }
                } else {
                    error(select.selected, "tailrec method changing 'this'");
                }
            } else if (tree.meth instanceof JCTree.JCIdent ident && ident.sym.baseSymbol() == decl.sym) {
                this.translate = makeTailCall(tree);
            }
        }
        push(false, () -> super.visitApply(tree));
    }

    private JCTree.JCStatement makeTailCall(JCTree.JCMethodInvocation invoke) {
        success++;
        make.at(invoke);
        // store arguments to temp locals, then store to params
        List<JCTree.JCStatement> paramsWrite = List.nil();
        List<JCTree.JCStatement> localsWrite = List.nil();
        var param = decl.params;
        var tempLocals = param.map(it -> new Symbol.VarSymbol(0, names.fromString("$").append(it.sym.name), it.sym.type, it.sym.owner));
        for (var args = invoke.args; !param.isEmpty(); param = param.tail, args = args.tail, tempLocals = tempLocals.tail) {
            localsWrite = localsWrite.append(make.VarDef(tempLocals.head, args.head));
            paramsWrite = paramsWrite.append(make.Assignment(param.head.sym, make.Ident(tempLocals.head)));
        }
        paramsWrite = paramsWrite.prependList(localsWrite);
        // continue outside switch expression is not allowed
        // however it seems javac can actually handle this...
        var cont = make.Continue(label);
        cont.target = labelTarget;
        paramsWrite = paramsWrite.append(cont);
        return make.Block(0, paramsWrite);
    }

    @Override
    public void visitClassDef(JCTree.JCClassDecl tree) {
        var old = decl;
        decl = null;
        push(false, () -> super.visitClassDef(tree));
        decl = old;
    }

    @Override
    public void visitVarDef(JCTree.JCVariableDecl tree) {
        if (decl != null) {
            push(false, () -> super.visitVarDef(tree));
        }
    }

    @Override
    public void visitNewClass(JCTree.JCNewClass tree) {
        push(false, () -> {
            scan(tree.encl);
            scan(tree.typeargs);
            scan(tree.clazz);
            scan(tree.args);
            var old = decl;
            decl = null;
            scan(tree.def);
            decl = old;
        });
    }

    @Override
    public void visitNewArray(JCTree.JCNewArray tree) {
        push(false, () -> super.visitNewArray(tree));
    }

    @Override
    public void visitLambda(JCTree.JCLambda tree) {
        var old = decl;
        decl = null;
        push(false, () -> scan(tree.body));
        decl = old;
    }

    @Override
    public void visitParens(JCTree.JCParens tree) {
        super.visitParens(tree);
    }

    @Override
    public void visitAssign(JCTree.JCAssign tree) {
        push(false, () -> super.visitAssign(tree));
    }

    @Override
    public void visitAssignop(JCTree.JCAssignOp tree) {
        push(false, () -> super.visitAssignop(tree));
    }

    @Override
    public void visitUnary(JCTree.JCUnary tree) {
        push(false, () -> super.visitUnary(tree));
    }

    @Override
    public void visitBinary(JCTree.JCBinary tree) {
        push(false, () -> super.visitBinary(tree));
    }

    @Override
    public void visitTypeCast(JCTree.JCTypeCast tree) {
        push(false, () -> super.visitTypeCast(tree));
    }

    @Override
    public void visitTypeTest(JCTree.JCInstanceOf tree) {
        push(false, () -> super.visitTypeTest(tree));
    }

    @Override
    public void visitBindingPattern(JCTree.JCBindingPattern tree) {
        push(false, () -> super.visitBindingPattern(tree));
    }

    @Override
    public void visitDefaultCaseLabel(JCTree.JCDefaultCaseLabel tree) {
    }

    @Override
    public void visitParenthesizedPattern(JCTree.JCParenthesizedPattern that) {
        push(false, () -> super.visitParenthesizedPattern(that));
    }

    @Override
    public void visitGuardPattern(JCTree.JCGuardPattern that) {
        push(false, () -> super.visitGuardPattern(that));
    }

    @Override
    public void visitIndexed(JCTree.JCArrayAccess tree) {
        push(false, () -> super.visitIndexed(tree));
    }

    @Override
    public void visitSelect(JCTree.JCFieldAccess tree) {
        push(false, () -> super.visitSelect(tree));
    }

    @Override
    public void visitReference(JCTree.JCMemberReference tree) {
        push(false, () -> super.visitReference(tree));
    }

    @Override
    public void visitIdent(JCTree.JCIdent tree) {
    }

    @Override
    public void visitLiteral(JCTree.JCLiteral tree) {
    }

    @Override
    public void visitTypeIdent(JCTree.JCPrimitiveTypeTree tree) {
    }

    @Override
    public void visitTypeArray(JCTree.JCArrayTypeTree tree) {
    }

    @Override
    public void visitExec(JCTree.JCExpressionStatement tree) {
        push(false, () -> super.visitExec(tree));
    }

    public void accept(CompilationUnitTree unit) {
        ((JCTree.JCCompilationUnit) unit).accept(this);
    }
}
