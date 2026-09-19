package com.datadog.featureflag;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.trace.api.intake.Intake;
import java.util.function.Supplier;

final class FeatureFlagEvpPublisher<T> extends EventPublisher<T> {
  FeatureFlagEvpPublisher(BackendApiFactory factory, Class<T> requestType) {
    this(factory, requestType, true);
  }

  FeatureFlagEvpPublisher(BackendApiFactory factory, Class<T> requestType, boolean compression) {
    this(() -> factory.createBackendApi(Intake.EVENT_PLATFORM, compression), requestType);
  }

  FeatureFlagEvpPublisher(Supplier<BackendApi> supplier, Class<T> requestType) {
    super(BackendEventTransport.adapt(supplier), requestType);
  }
}
