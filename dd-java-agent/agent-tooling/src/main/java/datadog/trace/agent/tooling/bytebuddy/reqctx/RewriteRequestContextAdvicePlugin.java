package datadog.trace.agent.tooling.bytebuddy.reqctx;

import datadog.trace.advice.RequiresRequestContext;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;

public class RewriteRequestContextAdvicePlugin extends Plugin.ForElementMatcher {
  public RewriteRequestContextAdvicePlugin(File targetDir) {
    super(ElementMatchers.isAnnotatedWith(RequiresRequestContext.class));
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(new InjectRequestContextVisitorWrapper());
  }

  @Override
  public void close() throws IOException {}
}
