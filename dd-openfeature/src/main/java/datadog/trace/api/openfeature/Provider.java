package datadog.trace.api.openfeature;

import java.util.concurrent.TimeUnit;

/**
 * The Datadog OpenFeature provider, under its former package name.
 *
 * @deprecated Use {@link com.datadog.openfeature.Provider} instead.
 */
@Deprecated
public class Provider extends com.datadog.openfeature.Provider {
  /** Creates a provider waiting up to 30 seconds for the initial flag configuration. */
  public Provider() {
    super();
  }

  /**
   * Creates a provider.
   *
   * @param options the provider options.
   */
  public Provider(final com.datadog.openfeature.Provider.Options options) {
    super(options);
  }

  /**
   * Creates a provider.
   *
   * @param options the provider options.
   */
  public Provider(final Options options) {
    super(options);
  }

  /**
   * The provider options, under their former package name.
   *
   * @deprecated Use {@link com.datadog.openfeature.Provider.Options} instead.
   */
  @Deprecated
  public static class Options extends com.datadog.openfeature.Provider.Options {
    @Override
    public Options initTimeout(final long timeout, final TimeUnit unit) {
      super.initTimeout(timeout, unit);
      return this;
    }
  }
}
