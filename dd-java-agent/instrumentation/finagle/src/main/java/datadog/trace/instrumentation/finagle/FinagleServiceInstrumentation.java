package datadog.trace.instrumentation.finagle;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.finagle.FinagleServiceDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.twitter.finagle.http.Response;
import com.twitter.util.Future;
import com.twitter.util.FutureEventListener;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDTags;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class FinagleServiceInstrumentation extends Instrumenter.Default {
  public FinagleServiceInstrumentation() {
    super("finagle");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.decorator.BaseDecorator",
      "datadog.trace.agent.decorator.ServerDecorator",
      packageName + ".FinagleServiceDecorator",
      FinagleServiceInstrumentation.class.getName() + "$Listener"
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return safeHasSuperType(named("com.twitter.finagle.Service"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("apply"))
            .and(takesArgument(0, named("com.twitter.finagle.http.Request"))),
        FinagleServiceInstrumentation.class.getName() + "$ServiceWrappingAdvice");
  }

  public static class ServiceWrappingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope startSpanOnApply(@Advice.Origin final Method method) {

      if (method
          .getDeclaringClass()
          .getName()
          .startsWith("com.twitter.finagle.http")) { // Ignore built in services

        final TraceScope parentScope = activeScope();
        if (parentScope != null) {
          parentScope.setAsyncPropagation(true);
        }
        return null;
      }

      final AgentSpan span = startSpan("finagle.service");
      DECORATE.afterStart(span);

      span.setTag(DDTags.RESOURCE_NAME, DECORATE.spanNameForMethod(method));

      final AgentScope scope = activateSpan(span, false);
      scope.setAsyncPropagation(true);

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addSpanFinisherOnExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Future<Response> result) {

      if (scope == null) {
        return;
      }

      final AgentSpan span = scope.span();

      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
      } else {
        result.addEventListener(new Listener(span));
      }

      scope.close();
    }
  }

  public static class Listener implements FutureEventListener<Response> {
    private final AgentSpan span;

    public Listener(final AgentSpan span) {
      this.span = span;
    }

    @Override
    public void onSuccess(final Response value) {
      DECORATE.beforeFinish(span);
      span.finish();
    }

    @Override
    public void onFailure(final Throwable cause) {
      DECORATE.onError(span, cause);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
