package datadog.trace.agent.tooling.bytebuddy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;

import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Bytebuddy gradle plugin which rewrites placeholder calls in 'NewTaskFor' advice at compile time.
 *
 * <p>Used for {@code
 * datadog.trace.instrumentation.java.concurrent.WrapRunnableAsNewTaskInstrumentation}
 * instrumentation, to replace {@code
 * datadog.trace.bootstrap.instrumentation.java.concurrent.NewTaskForPlaceholder#newTaskFor} by
 * {@code java.util.concurrent.AbstractExecutorService#newTaskFor(java.lang.Runnable, T)} which is
 * protected and not accessible during compilation.
 *
 * @see datadog.gradle.plugin.instrument.BuildTimeInstrumentationPlugin
 */
public class NewTaskForGradlePlugin extends Plugin.ForElementMatcher {
  private final File targetDir;

  public NewTaskForGradlePlugin(File targetDir) {
    super(nameEndsWith("$NewTaskFor"));
    this.targetDir = targetDir;
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(NewTaskForRewritingVisitor.INSTANCE);
  }

  @Override
  public void close() throws IOException {}
}
