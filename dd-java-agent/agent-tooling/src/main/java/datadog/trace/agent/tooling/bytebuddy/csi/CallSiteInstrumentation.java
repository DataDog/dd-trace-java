package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import javax.annotation.Nonnull;

/**
 * Instrumented using a {@link CallSiteTransformer} to perform the actual instrumentation. The
 * method {@link #callSites()} should be implemented by subclasses to fetch the actual {@link
 * CallSiteAdvice} implementations.
 */
public abstract class CallSiteInstrumentation extends Instrumenter.Default
    implements Instrumenter.ForCallSite {

  private Advices advices;

  public CallSiteInstrumentation(
      @Nonnull final String name, @Nonnull final String... additionalNames) {
    super(name, additionalNames);
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {}

  @Override
  public AdviceTransformer transformer() {
    return new CallSiteTransformer(name(), advices());
  }

  protected abstract CallSiteSupplier callSites();

  private Advices advices() {
    if (null == advices) {
      advices = Advices.fromCallSites(callSites().get());
    }
    return advices;
  }
}
