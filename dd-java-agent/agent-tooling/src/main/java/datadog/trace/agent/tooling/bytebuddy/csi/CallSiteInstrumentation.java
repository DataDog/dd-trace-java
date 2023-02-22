package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import java.util.ServiceLoader;
import javax.annotation.Nonnull;

/**
 * Instrumented using a {@link CallSiteTransformer} to perform the actual instrumentation. It will
 * fetch the instance of the {@link CallSiteAdvice} from SPI according to the specified marker
 * interface.
 */
public abstract class CallSiteInstrumentation extends Instrumenter.Default
    implements Instrumenter.ForCallSite {

  private final Class<?> spiInterface;
  private Advices advices;

  /**
   * Create a new instance of the instrumenter.
   *
   * @param spiInterface marker interface implemented by the chosen {@link CallSiteAdvice} instances
   */
  public CallSiteInstrumentation(
      @Nonnull final Class<?> spiInterface,
      @Nonnull final String name,
      @Nonnull final String... additionalNames) {
    super(name, additionalNames);
    this.spiInterface = spiInterface;
    this.advices = null;
  }

  protected CallSiteInstrumentation(
      @Nonnull final Iterable<CallSiteAdvice> advices,
      @Nonnull final String name,
      @Nonnull final String... additionalNames) {
    super(name, additionalNames);
    this.spiInterface = null;
    this.advices = Advices.fromCallSites(advices);
  }

  @SuppressWarnings("unchecked")
  private static Iterable<CallSiteAdvice> fetchAdvicesFromSpi(
      @Nonnull final Class<?> spiInterface) {
    final ClassLoader targetClassLoader = CallSiteInstrumentation.class.getClassLoader();
    return (ServiceLoader<CallSiteAdvice>) ServiceLoader.load(spiInterface, targetClassLoader);
  }

  @Override
  public String[] helperClassNames() {
    return advices().getHelpers();
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {}

  @Override
  public AdviceTransformer transformer() {
    return new CallSiteTransformer(advices());
  }

  private Advices advices() {
    if (null == advices) {
      advices = Advices.fromCallSites(fetchAdvicesFromSpi(spiInterface));
    }
    return advices;
  }
}
