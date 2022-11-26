package io.izzel.arcturus.processor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Source;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

public class ArcturusTypes {

    public static final Context.Key<ArcturusTypes> KEY = new Context.Key<>();

    public static ArcturusTypes instance(Context context) {
        var instance = context.get(KEY);
        if (instance == null) {
            instance = new ArcturusTypes(context);
        }
        return instance;
    }

    public ArcturusTypes(Context context) {
        context.put(KEY, this);
        symtab = Symtab.instance(context);
        names = Names.instance(context);
        types = Types.instance(context);
        if (Source.Feature.MODULES.allowedInSource(Source.instance(context))) {
            arcturusModule = symtab.unnamedModule;
        } else {
            arcturusModule = symtab.noModule;
        }
        link = typeOf("io.izzel.arcturus.api.Intrinsic$Link");
        intrinsic = typeOf("io.izzel.arcturus.api.Intrinsic");
        inline = typeOf("io.izzel.arcturus.api.Inline");
        tailrec = typeOf("io.izzel.arcturus.api.Tailrec");
        //specialized = typeOf(Specialized.class);
        linkValue = nameOf("value");
        linkOwner = nameOf("owner");
        linkBsmArgs = nameOf("bsmArgs");
        intrinsicBootstrap = new Symbol.MethodSymbol(
            Flags.PUBLIC | Flags.STATIC,
            nameOf("makeIntrinsic"),
            new Type.MethodType(List.of(symtab.methodHandleLookupType,
                symtab.stringType, symtab.methodTypeType, symtab.methodHandleType, types.makeArrayType(symtab.objectType)),
                typeOf("java.lang.invoke.CallSite"),
                List.nil(),
                symtab.methodClass),
            intrinsic.tsym
        );
    }

    private final Symtab symtab;
    private final Names names;
    private final Symbol.ModuleSymbol arcturusModule;
    public final Types types;
    public final Type link;
    public final Type intrinsic;
    public final Type inline;
    public final Type tailrec;

    public final Name linkValue;
    public final Name linkOwner;
    public final Name linkBsmArgs;

    public final Symbol.MethodSymbol intrinsicBootstrap;

    public Symbol noSymbol() {
        return symtab.noSymbol;
    }

    private Type typeOf(String name) {
        var classSymbol = symtab.enterClass(arcturusModule, names.fromString(name));
        return classSymbol.type;
    }

    public Name nameOf(String name) {
        return names.fromString(name);
    }
}
