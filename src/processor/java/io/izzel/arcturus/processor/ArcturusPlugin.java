package io.izzel.arcturus.processor;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.util.Context;

public class ArcturusPlugin implements Plugin, TaskListener {

    static {
        Unsafe.exportJdkModule();
    }

    private Context context;

    @Override
    public String getName() {
        return "arcturus";
    }

    @Override
    public void init(JavacTask task, String... args) {
        this.context = ((BasicJavacTask) task).getContext();
        task.addTaskListener(this);
        IntrinsicScan.ForReader.install(this.context);
    }

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() == TaskEvent.Kind.ENTER) {
            e.getCompilationUnit().accept(new IntrinsicScan(this.context), null);
            IntrinsicScan.ForReader.instance(context).flush();
        }
        if (e.getKind() == TaskEvent.Kind.ANALYZE) {
            e.getCompilationUnit().accept(new IntrinsicLink(this.context, e.getSourceFile()), null);
        }
    }

    @Override
    public boolean autoStart() {
        return true;
    }
}
