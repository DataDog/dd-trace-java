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
public abstract class CallSiteInstrumenter extends Instrumenter.Default
    implements Instrumenter.ForCallSite {

  private final Advices advices;

  /**
   * Create a new instance of the instrumenter.
   *
   * @param spiInterface marker interface implemented by the chosen {@link CallSiteAdvice} instances
   */
  public CallSiteInstrumenter(
      @Nonnull final Class<?> spiInterface,
      @Nonnull final String name,
      @Nonnull final String... additionalNames) {
    this(fetchAdvicesFromSpi(spiInterface), name, additionalNames);
  }

  protected CallSiteInstrumenter(
      @Nonnull final Iterable<CallSiteAdvice> advices,
      @Nonnull final String name,
      @Nonnull final String... additionalNames) {
    super(name, additionalNames);
    this.advices = Advices.fromCallSites(advices);
  }

  @SuppressWarnings("unchecked")
  private static Iterable<CallSiteAdvice> fetchAdvicesFromSpi(
      @Nonnull final Class<?> spiInterface) {
    final ClassLoader targetClassLoader = CallSiteInstrumenter.class.getClassLoader();
    return (ServiceLoader<CallSiteAdvice>) ServiceLoader.load(spiInterface, targetClassLoader);
  }

  @Override
  public String[] helperClassNames() {
    return advices.getHelpers();
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {}

  @Override
  public AdviceTransformer transformer() {
    return new CallSiteTransformer(advices);
  }
}
