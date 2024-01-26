package datadog.trace.instrumentation.restlet;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.restlet.ResourceDecorator.RESTLET_ROUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import org.restlet.Request;
import org.restlet.engine.header.Header;
import org.restlet.routing.TemplateRoute;
import org.restlet.util.Series;

@AutoService(Instrumenter.class)
public final class RouteInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public RouteInstrumentation() {
    super("restlet-http");
  }

  @Override
  public String instrumentedType() {
    return "org.restlet.routing.TemplateRoute";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("beforeHandle"))
            .and(takesArgument(0, named("org.restlet.Request")))
            .and(takesArgument(1, named("org.restlet.Response"))),
        getClass().getName() + "$RouteBeforeHandleAdvice");
  }

  public static class RouteBeforeHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beginRequest(
        @Advice.This final TemplateRoute route, @Advice.Argument(0) final Request request) {
      String pattern = route.getTemplate().getPattern();
      if (null == pattern || pattern.equals("")) {
        return;
      }

      Series<Header> headers =
          (Series<Header>) request.getAttributes().get("org.restlet.http.headers");
      headers.set(RESTLET_ROUTE, pattern);
    }
  }
}
