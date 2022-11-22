package io.izzel.arcturus.processor;

import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.jvm.PoolConstant;
import com.sun.tools.javac.resources.CompilerProperties.Errors;
import com.sun.tools.javac.resources.CompilerProperties.Fragments;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Pair;

import javax.tools.JavaFileObject;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.StreamSupport;

public class IntrinsicLink extends TreeScanner<Void, Tree> {

    private final ArcturusTypes arcturusTypes;
    private final Log log;
    private final TreeMaker make;
    private final Symtab symtab;
    private final Names names;

    public IntrinsicLink(Context context, JavaFileObject sourceFile) {
        this.arcturusTypes = ArcturusTypes.instance(context);
        this.log = Log.instance(context);
        this.make = TreeMaker.instance(context);
        this.symtab = Symtab.instance(context);
        this.names = Names.instance(context);
        this.log.useSource(sourceFile);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Tree tree) {
        scan(node.getTypeArguments(), null);
        // only pass argument expressions to member select
        reduce(scan(node.getMethodSelect(), node.getMethodSelect().getKind() == Tree.Kind.MEMBER_SELECT ? node : null), null);
        reduce(scan(node.getArguments(), null), null);
        return null;
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Tree invoke) {
        var fieldAccess = (JCTree.JCFieldAccess) node;
        // HYPOTHETICAL flag is added to all "virtual" method symbol generated from poly sig calls
        if ((fieldAccess.sym.flags_field & Flags.HYPOTHETICAL) != 0) {
            var baseSymbol = fieldAccess.sym.baseSymbol();
            for (Attribute.Compound annotationMirror : baseSymbol.getAnnotationMirrors()) {
                if (annotationMirror.type.tsym == arcturusTypes.link.tsym) {
                    buildIntrinsicCall(fieldAccess, (Symbol.MethodSymbol) fieldAccess.sym, (JCTree.JCMethodInvocation) invoke, annotationMirror);
                    break;
                }
            }
        }
        return super.visitMemberSelect(node, null);
    }

    private void buildIntrinsicCall(JCTree.JCFieldAccess node, Symbol.MethodSymbol symbol, JCTree.JCMethodInvocation invoke, Attribute.Compound link) {
        var pair = findLinkTarget(node, link, symbol.owner.type.tsym);
        if (pair == null) return;
        int bsmArgCount = pair.snd;
        var bsmArgs = new ListBuffer<PoolConstant.LoadableConstant>();
        bsmArgs.add(pair.fst);
        if (parseBsmArgs(node, symbol, invoke, bsmArgCount, bsmArgs)) return;
        transMethodType(node, symbol, invoke, bsmArgCount);
        node.sym = new Symbol.DynamicMethodSymbol(
            symbol.name,
            arcturusTypes.noSymbol(),
            arcturusTypes.intrinsicBootstrap.asHandle(),
            symbol.type,
            bsmArgs.toArray(new PoolConstant.LoadableConstant[0])
        );
    }

    private boolean parseBsmArgs(JCTree.JCFieldAccess node, Symbol.MethodSymbol symbol, JCTree.JCMethodInvocation invoke, int bsmArgCount, ListBuffer<PoolConstant.LoadableConstant> bsmArgs) {
        if (bsmArgCount > 0) {
            int n = 0;
            for (Iterator<JCTree.JCExpression> iterator = invoke.args.iterator(); n < bsmArgCount && iterator.hasNext(); n++) {
                var arg = iterator.next();
                var constant = parseBsmConstant(arg);
                if (constant == null) {
                    log.error(arg, Errors.ConstExprReq);
                    return true;
                } else {
                    bsmArgs.add(constant);
                }
            }
            if (n != bsmArgCount) {
                log.error(invoke, Errors.CantApplySymbol(Kinds.Kind.MTH, node.name, List.from(Collections.nCopies(bsmArgCount, symtab.objectType)),
                    invoke.args.map(it -> it.type), Kinds.Kind.TYP, symbol.owner.type, Fragments.ArgLengthMismatch));
            }
        }
        return false;
    }

    private PoolConstant.LoadableConstant parseBsmConstant(JCTree.JCExpression tree) {
        // JLS 15.29 Constant Expressions
        var value = tree.type.constValue();
        if (value instanceof String s) {
            return PoolConstant.LoadableConstant.String(s);
        } else if (value instanceof Integer i) {
            return PoolConstant.LoadableConstant.Int(i);
        } else if (value instanceof Long l) {
            return PoolConstant.LoadableConstant.Long(l);
        } else if (value instanceof Float f) {
            return PoolConstant.LoadableConstant.Float(f);
        } else if (value instanceof Double d) {
            return PoolConstant.LoadableConstant.Double(d);
        }
        // class literals, or "class" member select is not valid CE
        if (tree instanceof JCTree.JCFieldAccess select && select.sym.type.tsym == symtab.classType.tsym && select.sym.name == names._class) {
            return (Type.ClassType) select.sym.owner.type;
        }
        return null;
    }

    private void transMethodType(JCTree.JCFieldAccess node, Symbol.MethodSymbol symbol, JCTree.JCMethodInvocation invoke, int bsmArgCount) {
        // static without bsm args methods are called as is
        if (bsmArgCount == 0 && symbol.isStatic()) return;
        var old = (Type.MethodType) symbol.type;
        // bsm args are dropped from invoke arguments
        // non-static call are prepended with receiver expression
        var mt = drop(old.argtypes, bsmArgCount);
        if (!symbol.isStatic()) {
            mt = mt.prepend(node.selected.type);
        }
        var indyType = new Type.MethodType(mt, old.restype, old.thrown, old.tsym);
        indyType.recvtype = old.recvtype;
        node.type = symbol.type = indyType;
        invoke.args = drop(invoke.args, bsmArgCount);
        if (!symbol.isStatic()) {
            invoke.args = invoke.args.prepend(node.selected);
        }
        node.selected = make.at(node.selected).Ident(symbol.owner);
    }

    private static <A> List<A> drop(List<A> list, int n) {
        while (n-- > 0) {
            list = list.tail;
        }
        return list;
    }

    private Pair<Symbol.MethodHandleSymbol, Integer> findLinkTarget(JCTree.JCFieldAccess node, Attribute.Compound link, Symbol.TypeSymbol caller) {
        String value = null;
        Symbol.TypeSymbol owner = caller;
        int bsmArgs = 0;
        for (var pair : link.values) {
            if (pair.fst.name == arcturusTypes.linkValue) {
                value = (String) pair.snd.getValue();
            } else if (pair.fst.name == arcturusTypes.linkOwner) {
                var tsym = ((Type) pair.snd.getValue()).tsym;
                if (tsym != arcturusTypes.link.tsym) {
                    owner = tsym;
                }
            } else if (pair.fst.name == arcturusTypes.linkBsmArgs) {
                bsmArgs = (int) pair.snd.getValue();
            }
        }
        Objects.requireNonNull(value, "@Link(value)");
        var name = arcturusTypes.nameOf(value);
        var candidates = StreamSupport.stream(owner.members().getSymbolsByName(name).spliterator(), false)
            .filter(it -> it.kind == Kinds.Kind.MTH && it.type.getReturnType().tsym == arcturusTypes.intrinsic.tsym
                || it.kind == Kinds.Kind.VAR && it.type.tsym == arcturusTypes.intrinsic.tsym).toList();
        if (candidates.size() != 1) {
            if (candidates.isEmpty()) {
                log.error(JCDiagnostic.DiagnosticFlag.RESOLVE_ERROR, node, Errors.CantResolve(Kinds.KindName.METHOD, name, null, null));
            } else {
                log.error(JCDiagnostic.DiagnosticFlag.RESOLVE_ERROR, node, Errors.RefAmbiguous(
                    name,
                    candidates.get(0).kind, candidates.get(0), candidates.get(0).owner,
                    candidates.get(1).kind, candidates.get(1), candidates.get(1).owner
                ));
            }
            return null;
        } else {
            var target = candidates.get(0);
            if (!target.isStatic()) {
                log.error(node, Errors.NonStaticCantBeRef(target.kind, target));
                return null;
            }
            if (!canAccess(target, caller)) {
                log.error(node, Errors.CantAccess(target, Fragments.NotDefPublicCantAccess(target, target.owner)));
                return null;
            }
            return Pair.of(new Symbol.MethodHandleSymbol(target, true), bsmArgs);
        }
    }

    // TODO do this properly
    private boolean canAccess(Symbol symbol, Symbol type) {
        return symbol.isAccessibleIn(type, arcturusTypes.types) || (type.owner.kind == Kinds.Kind.TYP && canAccess(symbol, type.owner));
    }
}
