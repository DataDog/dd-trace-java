package com.datadog.featureflag;

import com.datadog.featureflag.exposure.ExposureEvent;

/**
 * Defines an exposure writer responsible for sending exposure events to the EVP proxy.
 * Implementations should use a background thread to perform these operations asynchronously.
 */
public interface ExposureWriter extends AutoCloseable {

  void init();

  void close();

  void write(ExposureEvent event);
}
