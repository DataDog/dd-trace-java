package datadog.trace.instrumentation.rxjava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.async.AsyncPropagationSuppressingInstrumentation;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Prevents periodic tasks and lazy scheduler initialization from retaining request context. */
@AutoService(InstrumenterModule.class)
public final class RxJavaAsyncSuppressionInstrumentation
    extends AsyncPropagationSuppressingInstrumentation
    implements Instrumenter.CanShortcutTypeMatching {

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "rx.internal.operators.OperatorTimeoutBase", "rx.internal.util.ObjectPool"
    };
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return false;
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("rx.").and(extendsClass(named("rx.Scheduler$Worker")));
  }

  @Override
  protected ElementMatcher<? super MethodDescription> suppressedMethods() {
    return named("schedulePeriodically")
        .and(isDeclaredBy(hierarchyMatcher()))
        .or(named("call").and(isDeclaredBy(named("rx.internal.operators.OperatorTimeoutBase"))))
        .or(named("start").and(isDeclaredBy(named("rx.internal.util.ObjectPool"))));
  }
}
