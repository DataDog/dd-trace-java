package datadog.trace.instrumentation.rxjava2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class SingleInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SingleInstrumentation() {
    super("rxjava");
  }

  @Override
  public String instrumentedType() {
    return "io.reactivex.Single";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingSingleObserver",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.reactivex.Single", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureParentSpanAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.reactivex.SingleObserver"))),
        getClass().getName() + "$PropagateParentSpanAdvice");
  }

  public static class CaptureParentSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Single<?> single) {
      AgentSpan parentSpan = activeSpan();
      if (parentSpan != null) {
        InstrumentationContext.get(Single.class, AgentSpan.class).put(single, parentSpan);
      }
    }
  }

  public static class PropagateParentSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onSubscribe(
        @Advice.This final Single<?> single,
        @Advice.Argument(value = 0, readOnly = false) SingleObserver<?> observer) {
      if (observer != null) {
        AgentSpan parentSpan =
            InstrumentationContext.get(Single.class, AgentSpan.class).get(single);
        if (parentSpan != null) {
          // wrap the observer so spans from its events treat the captured span as their parent
          observer = new TracingSingleObserver<>(observer, parentSpan);
          // activate the span here in case additional observers are created during subscribe
          return activateSpan(parentSpan);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
