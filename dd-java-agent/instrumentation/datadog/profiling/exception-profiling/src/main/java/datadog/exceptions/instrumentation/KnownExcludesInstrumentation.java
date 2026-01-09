package datadog.exceptions.instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;

/**
 * Provides instrumentation to exclude exception types known to be used for control flow or
 * 'connection leak' detection.
 */
@AutoService(InstrumenterModule.class)
public final class KnownExcludesInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public KnownExcludesInstrumentation() {
    // this instrumentation is controlled together with 'throwables' instrumentation
    super("throwables");
  }

  @Override
  public boolean isEnabled() {
    return Platform.hasJfr() && super.isEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".ExclusionAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"com.zaxxer.hikari.pool.ProxyLeakTask"};
  }
}
