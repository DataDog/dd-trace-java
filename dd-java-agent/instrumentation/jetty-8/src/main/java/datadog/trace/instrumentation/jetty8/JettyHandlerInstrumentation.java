package datadog.trace.instrumentation.jetty8;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JettyHandlerInstrumentation extends Instrumenter.Default {

  public JettyHandlerInstrumentation() {
    super("jetty", "jetty-8");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.eclipse.jetty.server.Handler");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return not(named("org.eclipse.jetty.server.handler.HandlerWrapper"))
        .and(implementsInterface(named("org.eclipse.jetty.server.Handler")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyDecorator",
      packageName + ".HttpServletRequestExtractAdapter",
      packageName + ".TagSettingAsyncListener"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("handle")
            .and(takesArgument(0, named("java.lang.String")))
            .and(takesArgument(1, named("org.eclipse.jetty.server.Request")))
            .and(takesArgument(2, named("javax.servlet.http.HttpServletRequest")))
            .and(takesArgument(3, named("javax.servlet.http.HttpServletResponse")))
            .and(isPublic()),
        packageName + ".JettyHandlerAdvice");
  }
}
