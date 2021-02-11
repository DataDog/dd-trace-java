package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.DECORATE;
import static datadog.trace.instrumentation.synapse3.SynapseServerDecorator.SYNAPSE_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpResponse;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.synapse.transport.passthru.SourceRequest;

@AutoService(Instrumenter.class)
public final class SynapseServerInstrumentation extends Instrumenter.Tracing {
  private static final String SYNAPSE_SPAN = "dd.trace.synapse.span";

  public SynapseServerInstrumentation() {
    super("synapse3");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
        "org.apache.synapse.transport.passthru.ServerWorker",
        "org.apache.synapse.transport.passthru.SourceResponse");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SynapseServerDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isMethod()
            .and(named("run"))
            .and(takesNoArguments())
            .and(isDeclaredBy(named("org.apache.synapse.transport.passthru.ServerWorker"))),
        getClass().getName() + "$HandleRequestAdvice");
    transformers.put(
        isMethod()
            .and(named("start"))
            .and(takesArgument(0, named("org.apache.http.nio.NHttpServerConnection")))
            .and(isDeclaredBy(named("org.apache.synapse.transport.passthru.SourceResponse"))),
        getClass().getName() + "$HandleAsyncResponseAdvice");
    return transformers;
  }

  public static final class HandleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.FieldValue("request") final SourceRequest request) {
      AgentSpan span = startSpan(SYNAPSE_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, request.getConnection());
      DECORATE.onRequest(span, request);
      request.getConnection().getContext().setAttribute(SYNAPSE_SPAN, span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishRequest(
        @Advice.Enter final AgentScope scope,
        @Advice.FieldValue("request") final SourceRequest request,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      HttpResponse response = request.getConnection().getHttpResponse();
      if (null != response) {
        DECORATE.onResponse(span, response);
      }
      if (null != error) {
        DECORATE.onError(span, error);
      }
      // if we have a response (or error) then we can finish the request span now
      if ((null != response || null != error)
          && null != request.getConnection().getContext().removeAttribute(SYNAPSE_SPAN)) {
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      } else {
        // response will be committed asynchronously by SourceResponse.start(connection)
        scope.close();
      }
    }
  }

  public static final class HandleAsyncResponseAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishAsyncResponse(
        @Advice.Argument(0) final NHttpServerConnection connection,
        @Advice.Thrown final Throwable error) {
      // get back to original span so we can finish it with the async response
      AgentSpan span = (AgentSpan) connection.getContext().removeAttribute(SYNAPSE_SPAN);
      if (null == span) {
        return;
      }
      try (AgentScope scope = activateSpan(span)) {
        HttpResponse response = connection.getHttpResponse();
        if (null != response) {
          DECORATE.onResponse(span, response);
        }
        if (null != error) {
          DECORATE.onError(span, error);
        }
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
