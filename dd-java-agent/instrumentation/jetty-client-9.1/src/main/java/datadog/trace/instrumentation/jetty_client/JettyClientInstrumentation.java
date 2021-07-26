package datadog.trace.instrumentation.jetty_client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.instrumentation.jetty_client.JettyClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty_client.JettyClientDecorator.HTTP_REQUEST;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

@AutoService(Instrumenter.class)
public class JettyClientInstrumentation extends Instrumenter.Tracing {
  public JettyClientInstrumentation() {
    super("jetty-client");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.client.HttpClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyClientDecorator",
      packageName + ".HeadersInjectAdapter",
      packageName + ".SpanFinishingCompleteListener",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.eclipse.jetty.client.HttpRequest", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("send"))
            .and(takesArgument(0, named("org.eclipse.jetty.client.api.Request")))
            .and(takesArgument(1, List.class)),
        getClass().getName() + "$SendAdvice");
  }

  public static class SendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) final Request request,
        @Advice.Argument(1) final List<Response.ResponseListener> listeners) {
      return activateSpan(DECORATE.prepareSpan(startSpan(HTTP_REQUEST), request, listeners));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final Request request,
        @Advice.Thrown final Throwable throwable) {
      if (throwable == null) {
        // capture context for JettyListenerInstrumentation
        capture(
            InstrumentationContext.get(HttpRequest.class, State.class),
            (HttpRequest) request,
            true);
      } else {
        DECORATE.onError(scope, throwable);
        DECORATE.beforeFinish(scope);
        scope.span().finish();
      }
      scope.close();
    }
  }
}
