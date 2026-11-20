package datadog.trace.instrumentation.feign;

import static datadog.context.Context.current;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.DECORATE;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.FEIGN;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.feign.RequestHeaderInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import feign.AsyncClient;
import feign.Request;
import feign.Response;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class FeignAsyncClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public FeignAsyncClientInstrumentation() {
    super("feign", "feign-10.8");
  }

  @Override
  public String hierarchyMarkerType() {
    return "feign.AsyncClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".FeignClientDecorator",
      packageName + ".RequestHeaderInjectAdapter",
      packageName + ".SpanFinishingCallback",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(isPublic())
            .and(takesArguments(3))
            .and(takesArgument(0, named("feign.Request")))
            .and(takesArgument(1, named("feign.Request$Options"))),
        FeignAsyncClientInstrumentation.class.getName() + "$AsyncExecuteAdvice");
  }

  public static class AsyncExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(value = 0, readOnly = false) Request request) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(AsyncClient.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(FEIGN.toString(), HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);

      final AgentScope scope = activateSpan(span);

      // Inject trace context into request headers
      Map<String, Collection<String>> injectedHeaders = new LinkedHashMap<>();
      DECORATE.injectContext(current(), injectedHeaders, SETTER);
      request = RequestHeaderInjectAdapter.inject(request, injectedHeaders);

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) CompletableFuture<Response> future,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      final AgentSpan span = scope.span();
      scope.close();
      CallDepthThreadLocalMap.reset(AsyncClient.class);

      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
        return;
      }

      if (future != null) {
        future = future.whenComplete(new SpanFinishingCallback(span));
      } else {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
