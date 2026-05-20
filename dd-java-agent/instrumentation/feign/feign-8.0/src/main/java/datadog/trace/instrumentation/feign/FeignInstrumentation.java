package datadog.trace.instrumentation.feign;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.DECORATE;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.FEIGN_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import feign.Request;
import feign.Response;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class FeignInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public FeignInstrumentation() {
    super("feign");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Feign 8.0 removed Dagger dependency, so check for absence of dagger-related inject adapters
    return hasClassNamed("feign.Param")
        .and(not(hasClassNamed("feign.Client$Default$$InjectAdapter")));
  }

  @Override
  public String instrumentedType() {
    return "feign.Client";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".FeignClientDecorator", packageName + ".RequestInjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("feign.Request")))
            .and(takesArgument(1, named("feign.Request$Options"))),
        FeignInstrumentation.class.getName() + "$FeignClientAdvice");
  }

  public static class FeignClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(0) Request request) {
      AgentSpan span = startSpan(FEIGN_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);

      // Inject headers into the request's mutable headers map
      DECORATE.injectHeaders(request.headers());

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Response response,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      try {
        AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        if (response != null) {
          DECORATE.onResponse(span, response);
        }
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        scope.close();
      }
    }
  }
}
