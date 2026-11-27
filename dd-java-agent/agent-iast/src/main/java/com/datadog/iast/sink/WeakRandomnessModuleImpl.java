package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.iast.sink.WeakRandomnessModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Locale;
import javax.annotation.Nonnull;

public class WeakRandomnessModuleImpl extends SinkModuleBase implements WeakRandomnessModule {

  /**
   * Whether a random implementation counts as secure. It only depends on the class, and this hook
   * runs on every call to a random method, so the check is done once per class.
   */
  private static final ClassValue<Boolean> SECURED =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
          return type.getSimpleName().toLowerCase(Locale.ROOT).contains("secure");
        }
      };

  public WeakRandomnessModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onWeakRandom(@Nonnull final Class<?> instance) {
    if (isSecuredInstance(instance)) {
      return;
    }
    // Same steps as report(type, evidence), but the evidence (which allocates its own context) is
    // only built once there is quota to report it: almost every call ends at the quota check.
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(
        Operations.REPORT_VULNERABILITY, span, VulnerabilityType.WEAK_RANDOMNESS)) {
      return;
    }
    final Evidence evidence = new Evidence(instance.getName());
    reporter.report(
        span,
        new Vulnerability(VulnerabilityType.WEAK_RANDOMNESS, buildLocation(span, null), evidence));
  }

  /**
   * Skip vulnerabilities on {@link java.security.SecureRandom} or any impl that contains secure in
   * the name
   */
  private static boolean isSecuredInstance(@Nonnull final Class<?> instance) {
    return SECURED.get(instance);
  }
}
