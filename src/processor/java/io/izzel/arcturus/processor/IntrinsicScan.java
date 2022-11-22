package io.izzel.arcturus.processor;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;

import java.util.ArrayDeque;
import java.util.Queue;

public class IntrinsicScan extends TreeScanner<Void, Void> {

    private final ArcturusTypes arcturusTypes;

    public IntrinsicScan(Context context) {
        arcturusTypes = ArcturusTypes.instance(context);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
        // intrinsic links declared in compiled source
        var symbol = ((JCTree.JCMethodDecl) node).sym;
        attrSigPoly(arcturusTypes, symbol);
        return super.visitMethod(node, unused);
    }

    private static void attrSigPoly(ArcturusTypes arcturusTypes, Symbol.MethodSymbol symbol) {
        for (Attribute.Compound annotationMirror : symbol.getAnnotationMirrors()) {
            if (annotationMirror.type.tsym == arcturusTypes.link.tsym) {
                symbol.flags_field |= Flags.SIGNATURE_POLYMORPHIC;
                break;
            }
        }
    }

    // intrinsic links declared in classpath classes
    static class ForReader implements Symbol.Completer {

        private static final Context.Key<ForReader> KEY = new Context.Key<>();

        static ForReader instance(Context context) {
            return context.get(KEY);
        }

        private final Symbol.Completer completer;
        private final Context context;
        private final Queue<Symbol.ClassSymbol> queue = new ArrayDeque<>();

        ForReader(Symbol.Completer completer, Context context) {
            context.put(KEY, this);
            this.completer = completer;
            this.context = context;
        }

        @Override
        public void complete(Symbol sym) {
            completer.complete(sym);
            if (sym.kind == Kinds.Kind.TYP) {
                // defer parse poly sig due to javac restriction,
                // annotations is not available now
                queue.add((Symbol.ClassSymbol) sym);
            }
        }

        @Override
        public boolean isTerminal() {
            return completer.isTerminal();
        }

        public void flush() {
            var types = ArcturusTypes.instance(context);
            for (var sym : queue) {
                for (var symbol : sym.members_field.getSymbols()) {
                    if (symbol.kind == Kinds.Kind.MTH) {
                        attrSigPoly(types, (Symbol.MethodSymbol) symbol);
                    }
                }
            }
            queue.clear();
        }

        static void install(Context context) {
            try {
                var symtab = Symtab.instance(context);
                var completer = (Symbol.Completer) Unsafe.lookup().findGetter(Symtab.class, "initialCompleter", Symbol.Completer.class).invoke(symtab);
                Unsafe.lookup().findSetter(Symtab.class, "initialCompleter", Symbol.Completer.class).invoke(symtab, new ForReader(completer, context));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }
}
