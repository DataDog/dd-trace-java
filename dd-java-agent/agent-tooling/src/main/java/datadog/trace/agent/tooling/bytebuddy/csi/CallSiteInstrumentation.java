package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import javax.annotation.Nonnull;

/**
 * Instrumented using a {@link CallSiteTransformer} to perform the actual instrumentation. The
 * method {@link #callSites()} should be implemented by subclasses to fetch the actual {@link
 * CallSiteAdvice} implementations.
 */
public abstract class CallSiteInstrumentation extends InstrumenterModule
    implements Instrumenter.ForCallSite, Instrumenter.HasTypeAdvice {

  private Advices advices;

  public CallSiteInstrumentation(
      @Nonnull final String name, @Nonnull final String... additionalNames) {
    super(name, additionalNames);
  }

  @Override
  public TargetSystem targetSystem() {
    return TargetSystem.SECURITY;
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new CallSiteTransformer(name(), advices()));
  }

  /** Utility to be able to tune the advices in subclasses */
  protected Advices buildAdvices(final Iterable<CallSites> callSites) {
    return Advices.fromCallSites(callSites);
  }

  protected abstract CallSiteSupplier callSites();

  private Advices advices() {
    if (null == advices) {
      advices = buildAdvices(callSites().get());
    }
    return advices;
  }
}
