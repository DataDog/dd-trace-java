package datadog.compiler;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.io.PrintWriter;
import java.net.URI;
import javax.tools.JavaFileObject;

public class DatadogCompilerPlugin implements Plugin {

  static final String NAME = "DatadogCompilerPlugin";

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void init(JavacTask task, String... strings) {
    if (task instanceof BasicJavacTask) {
      BasicJavacTask basicJavacTask = (BasicJavacTask) task;
      Context context = basicJavacTask.getContext();

      task.addTaskListener(new DatadogCompilerPluginTaskListener(context));
      Log.instance(context).printRawLines(Log.WriterKind.NOTICE, NAME + " initialized");
    }
  }

  private static final class DatadogCompilerPluginTaskListener implements TaskListener {
    private final Context context;

    private DatadogCompilerPluginTaskListener(Context context) {
      this.context = context;
    }

    @Override
    public void started(TaskEvent e) {}

    @Override
    public void finished(TaskEvent e) {
      if (e.getKind() != TaskEvent.Kind.PARSE) {
        return;
      }

      try {
        JavaFileObject sourceFile = e.getSourceFile();
        URI sourceUri = sourceFile.toUri();
        String sourcePath = sourceUri.getPath();

        SourcePathInjectingClassVisitor treeVisitor =
            new SourcePathInjectingClassVisitor(context, sourcePath);
        e.getCompilationUnit().accept(treeVisitor, null);

      } catch (Throwable t) {
        Log log = Log.instance(context);
        log.printRawLines(
            Log.WriterKind.WARNING,
            "Could not inject source path field into "
                + log.currentSourceFile().toUri()
                + ": "
                + t.getMessage());

        PrintWriter logWriter = log.getWriter(Log.WriterKind.WARNING);
        t.printStackTrace(logWriter);
      }
    }
  }

  private static final class SourcePathInjectingClassVisitor extends TreeScanner<Void, Void> {

    private final Context context;
    private final String sourcePath;

    private SourcePathInjectingClassVisitor(Context context, String sourcePath) {
      this.context = context;
      this.sourcePath = sourcePath;
    }

    @Override
    public Void visitClass(ClassTree node, Void aVoid) {
      TreeMaker maker = TreeMaker.instance(context);
      Names names = Names.instance(context);
      Symtab symtab = Symtab.instance(context);

      JCTree.JCModifiers modifiers = maker.Modifiers(Flags.PRIVATE | Flags.STATIC | Flags.FINAL);
      Name name = names.fromString(CompilerUtils.SOURCE_PATH_INJECTED_FIELD_NAME);
      JCTree.JCExpression type = maker.Type(symtab.stringType);
      JCTree.JCLiteral value = maker.Literal(sourcePath);

      JCTree.JCVariableDecl sourcePathField = maker.VarDef(modifiers, name, type, value);
      JCTree.JCClassDecl classDeclaration = (JCTree.JCClassDecl) node;
      classDeclaration.defs = classDeclaration.defs.prepend(sourcePathField);

      return super.visitClass(node, aVoid);
    }
  }
}
