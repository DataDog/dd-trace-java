package com.datadog.featureflag;

import datadog.trace.api.featureflag.FeatureFlaggingGateway.ExposureListener;

/**
 * Defines an exposure writer responsible for sending exposure events to the EVP proxy.
 * Implementations should use a background thread to perform these operations asynchronously.
 */
public interface ExposureWriter extends AutoCloseable, ExposureListener {

  void init();

  void close();
}
