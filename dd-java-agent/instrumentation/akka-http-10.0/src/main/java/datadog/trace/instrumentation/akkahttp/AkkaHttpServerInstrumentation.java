package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders.GETTER;
import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import akka.NotUsed;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.scaladsl.Flow;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class AkkaHttpServerInstrumentation extends Instrumenter.Default {
  public AkkaHttpServerInstrumentation() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("akka.http.scaladsl.HttpExt");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      AkkaHttpServerInstrumentation.class.getName() + "$DatadogWrapperHelper",
      packageName + ".AkkaServerFlowWrapper",
      packageName + ".AkkaServerFlowWrapper$WrappedServerGraphStage",
      packageName + ".AkkaServerFlowWrapper$WrappedServerFlowLogic",
      packageName + ".AkkaServerFlowWrapper$HttpRequestInHandler",
      packageName + ".AkkaServerFlowWrapper$HttpRequestOutHandler",
      packageName + ".AkkaServerFlowWrapper$HttpResponseInHandler",
      packageName + ".AkkaServerFlowWrapper$HttpResponseOutHandler",
      packageName + ".AkkaHttpServerHeaders",
      "datadog.trace.agent.decorator.BaseDecorator",
      "datadog.trace.agent.decorator.ServerDecorator",
      "datadog.trace.agent.decorator.HttpServerDecorator",
      packageName + ".AkkaHttpServerDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        named("bindAndHandle").and(takesArgument(0, named("akka.stream.scaladsl.Flow"))),
        AkkaHttpServerInstrumentation.class.getName() + "$AkkaHttpFlowAdvice");
    return transformers;
  }

  public static class AkkaHttpFlowAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapFlow(
        @Advice.Argument(value = 0, readOnly = false)
            Flow<HttpRequest, HttpResponse, NotUsed> handler) {
      handler = AkkaServerFlowWrapper.wrap(handler);
    }
  }

  public static class DatadogWrapperHelper {
    public static AgentScope createSpan(final HttpRequest request) {
      final AgentSpan.Context extractedContext = propagate().extract(request, GETTER);
      final AgentSpan span = startSpan("akka-http.request", extractedContext);

      DECORATE.afterStart(span);
      DECORATE.onConnection(span, request);
      DECORATE.onRequest(span, request);

      final AgentScope scope = activateSpan(span, false);
      scope.setAsyncPropagation(true);
      return scope;
    }

    public static void finishSpan(final AgentSpan span, final HttpResponse response) {
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);

      final TraceScope scope = activeScope();
      if (scope != null) {
        scope.setAsyncPropagation(false);
      }
      span.finish();
    }

    public static void finishSpan(final AgentSpan span, final Throwable t) {
      DECORATE.onError(span, t);
      span.setTag(Tags.HTTP_STATUS, 500);
      DECORATE.beforeFinish(span);

      final TraceScope scope = activeScope();
      if (scope != null) {
        scope.setAsyncPropagation(false);
      }
      span.finish();
    }
  }
}
