package datadog.trace.api.openfeature;

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
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DDProviderInitializer implements Initializer, ServerConfigurationListener {

  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private final CountDownLatch configurationLatch = new CountDownLatch(1);
  private EventProvider eventProvider;
  private RemoteConfigServiceImpl remoteConfigService;
  private ExposureWriter exposureWriter;
  private FeatureFlagEvaluator evaluator;

  @Override
  public boolean init(final EventProvider provider, final long timeout, final TimeUnit timeUnit)
      throws Exception {
    final Config config = Config.get();
    final SharedCommunicationObjects sco = new SharedCommunicationObjects();
    eventProvider = provider;
    exposureWriter = new ExposureWriterImpl(sco, config);
    evaluator = new FeatureFlagEvaluatorImpl(exposureWriter);
    remoteConfigService = new RemoteConfigServiceImpl(sco, config, this);
    return configurationLatch.await(timeout, timeUnit);
  }

  @Override
  public void close() {
    remoteConfigService.close();
    exposureWriter.close();
  }

  @Override
  public FeatureFlagEvaluator evaluator() {
    return evaluator;
  }

  @Override
  public ExposureWriter exposureWriter() {
    return exposureWriter;
  }

  @Override
  public RemoteConfigService remoteConfigService() {
    return remoteConfigService;
  }

  @Override
  public void onConfiguration(final ServerConfiguration configuration) {
    evaluator.onConfiguration(configuration);
    if (!initialized.getAndSet(true)) {
      eventProvider.emit(ProviderEvent.PROVIDER_READY, ProviderEventDetails.builder().build());
    } else {
      eventProvider.emit(
          ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, ProviderEventDetails.builder().build());
    }
    configurationLatch.countDown();
  }
}
