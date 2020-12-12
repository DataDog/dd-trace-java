package datadog.trace.agent.tooling.bytebuddy;

import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;

import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Bytebuddy gradle plugin which rewrites placeholder calls in 'NewTaskFor' advice at build time.
 */
public class NewTaskForGradlePlugin extends Plugin.ForElementMatcher {

  public NewTaskForGradlePlugin() {
    super(nameEndsWith("$NewTaskFor"));
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
