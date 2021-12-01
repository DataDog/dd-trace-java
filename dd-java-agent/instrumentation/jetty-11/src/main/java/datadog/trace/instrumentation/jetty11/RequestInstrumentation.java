package datadog.trace.instrumentation.jetty11;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class RequestInstrumentation extends Instrumenter.Tracing {

  public RequestInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.server.Request");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("setContext")
            .and(takesArgument(0, named("org.eclipse.jetty.server.handler.ContextHandler$Context")))
            .and(takesArgument(1, String.class)),
        packageName + ".SetContextPathAdvice");
  }
}
