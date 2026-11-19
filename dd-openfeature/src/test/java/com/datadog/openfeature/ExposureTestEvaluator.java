package com.datadog.openfeature;

import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import dev.openfeature.sdk.EvaluationContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A Datadog evaluator that runs without a runtime: it owns its exposure cache and captures the
 * exposures it would send to Datadog.
 */
class ExposureTestEvaluator extends DDEvaluator {
  final ExposureDeduplicationCache cache = new ExposureDeduplicationCache();
  final List<ExposureEvent> sentToDatadog = new CopyOnWriteArrayList<>();
  private final boolean sendToDatadog;

  /**
   * Creates an evaluator holding a configuration.
   *
   * @param configuration the flag configuration to evaluate.
   * @param sendToDatadog whether exposures are sent to Datadog.
   */
  ExposureTestEvaluator(final ServerConfiguration configuration, final boolean sendToDatadog) {
    super(mock(Runnable.class), AgentBridge.NONE, TestSettings.of());
    this.sendToDatadog = sendToDatadog;
    accept(configuration);
  }

  @Override
  public synchronized boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) {
    return hasConfiguration();
  }

  @Override
  ExposureDeduplicationCache exposureCache() {
    return this.cache;
  }

  @Override
  boolean isExposureDeliveryEnabled() {
    return this.sendToDatadog;
  }

  @Override
  void recordExposure(final ExposureEvent event) {
    this.sentToDatadog.add(event);
  }
}
