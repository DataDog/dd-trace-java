package datadog.trace.instrumentation.vertx_3_4.server;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpServerResponseEndHandlerInstrumentation extends Instrumenter.Default {
  public HttpServerResponseEndHandlerInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".EndHandlerWrapper",
      packageName + ".VertxRouterDecorator",
      packageName + ".VertxRouterDecorator$1",
      packageName + ".VertxRouterDecorator$VertxURIDataAdapter",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("io.vertx.core.http.impl.HttpServerResponseImpl");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("endHandler"))
            .and(isPublic())
            .and(takesArgument(0, named("io.vertx.core.Handler"))),
        packageName + ".EndHandlerWrapperAdvice");
  }
}
