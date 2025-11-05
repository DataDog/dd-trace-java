package datadog.trace.api.openfeature;

import datadog.trace.api.openfeature.config.RemoteConfigService;
import datadog.trace.api.openfeature.evaluator.FeatureFlagEvaluator;
import datadog.trace.api.openfeature.exposure.ExposureWriter;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.EventProvider;
import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;

public interface Initializer {

  boolean init(EventProvider provider, long timeout, TimeUnit timeUnit) throws Exception;

  void close();

  RemoteConfigService remoteConfigService();

  FeatureFlagEvaluator evaluator();

  ExposureWriter exposureWriter();

  /**
   * Uses reflection to load the initializer so we are in control of the loading of internal Datadog
   * classes
   */
  static Initializer withReflection(final String initializerClass) {
    return new Initializer() {

      private Initializer delegate;

      @Override
      public boolean init(final EventProvider provider, final long timeout, final TimeUnit timeUnit)
          throws Exception {
        delegate = loadInitializer();
        return delegate.init(provider, timeout, timeUnit);
      }

      @Override
      public void close() {
        delegate.close();
      }

      @Override
      public FeatureFlagEvaluator evaluator() {
        return delegate.evaluator();
      }

      @Override
      public ExposureWriter exposureWriter() {
        return delegate.exposureWriter();
      }

      @Override
      public RemoteConfigService remoteConfigService() {
        return delegate.remoteConfigService();
      }

      @SuppressForbidden // Class#forName(String) used to lazy load Datadog internal dependencies
      private Initializer loadInitializer() throws Exception {
        final Class<?> clazz = Class.forName(initializerClass);
        final Constructor<?> constructor = clazz.getConstructor();
        return (Initializer) constructor.newInstance();
      }
    };
  }
}
