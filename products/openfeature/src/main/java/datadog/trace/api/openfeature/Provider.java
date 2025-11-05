package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.openfeature.config.RemoteConfigService;
import datadog.trace.api.openfeature.config.RemoteConfigServiceImpl;
import datadog.trace.api.openfeature.config.ServerConfigurationListener;
import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration;
import datadog.trace.api.openfeature.evaluator.FeatureFlagEvaluator;
import datadog.trace.api.openfeature.evaluator.FeatureFlagEvaluatorImpl;
import datadog.trace.api.openfeature.exposure.ExposureWriter;
import datadog.trace.api.openfeature.exposure.ExposureWriterImpl;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.OpenFeatureError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Provider extends EventProvider implements Metadata, ServerConfigurationListener {

  static final String METADATA = "datadog-openfeature-provider";
  static final Options DEFAULT_OPTIONS = new Options().initTimeout(30, SECONDS);

  private final Options options;
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private final CountDownLatch configurationLatch = new CountDownLatch(1);
  private final SharedCommunicationObjects sco;
  private final Config config;
  private final RemoteConfigService remoteConfigService;
  private final ExposureWriter exposureWriter;
  private final FeatureFlagEvaluator evaluator;

  public Provider() {
    this(DEFAULT_OPTIONS);
  }

  public Provider(final Options options) {
    this(options, new SharedCommunicationObjects(), Config.get());
  }

  Provider(final Options options, final SharedCommunicationObjects sco, final Config config) {
    this.options = options;
    this.sco = sco;
    this.config = config;
    exposureWriter = new ExposureWriterImpl(sco, config);
    evaluator = new FeatureFlagEvaluatorImpl(exposureWriter);
    remoteConfigService = new RemoteConfigServiceImpl(sco, config, this);
  }

  @Override
  public void initialize(final EvaluationContext context) throws Exception {
    try {
      sco.createRemaining(config);
      final DDAgentFeaturesDiscovery discovery = sco.featuresDiscovery(config);
      discovery.discover();
      exposureWriter.init();
      remoteConfigService.init();
      if (!configurationLatch.await(options.getTimeout(), options.getUnit())) {
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
    } catch (final OpenFeatureError e) {
      throw e;
    } catch (final Throwable e) {
      throw new FatalError("Failed to initialize provider", e);
    }
  }

  @Override
  public void shutdown() {
    exposureWriter.close();
    remoteConfigService.close();
  }

  @Override
  public void onConfiguration(final ServerConfiguration configuration) {
    evaluator.onConfiguration(configuration);
    if (!initialized.getAndSet(true)) {
      emit(ProviderEvent.PROVIDER_READY, ProviderEventDetails.builder().build());
    } else {
      emit(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, ProviderEventDetails.builder().build());
    }
    configurationLatch.countDown();
  }

  @Override
  public Metadata getMetadata() {
    return this;
  }

  @Override
  public String getName() {
    return METADATA;
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      final String key, final Boolean defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Boolean.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      final String key, final String defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(String.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      final String key, final Integer defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Integer.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      final String key, final Double defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Double.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      final String key, final Value defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Value.class, key, defaultValue, ctx);
  }

  public static class Options {

    private long timeout;
    private TimeUnit unit;

    public Options initTimeout(final long timeout, final TimeUnit unit) {
      this.timeout = timeout;
      this.unit = unit;
      return this;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getUnit() {
      return unit;
    }
  }
}
