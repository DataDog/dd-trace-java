package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import reactor.core.CoreSubscriber;

@AutoService(InstrumenterModule.class)
public class CoreSubscriberInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public CoreSubscriberInstrumentation() {
    super("reactor-core");
  }

  @Override
  public String hierarchyMarkerType() {
    return "reactor.core.CoreSubscriber";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpanExtractorHelper",
      packageName + ".SpanExtractorHelper$1",
      packageName + ".SpanExtractorHelper$CachedDelegateCaller",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("onNext", "onComplete", "onError"),
        getClass().getName() + "$PropagateSpanInScopeAdvice");
  }

  public static class PropagateSpanInScopeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(@Advice.This final CoreSubscriber<?> self) {
      if (self.currentContext().hasKey("dd.span")) {
        final AgentSpan span = SpanExtractorHelper.maybeFrom(self.currentContext().get("dd.span"));
        if (span != null) {
          return activateSpan(span);
        }
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
