package com.datadog.featureflag.core;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically publishes one validated snapshot for synchronous evaluation. */
public final class ConfigurationStore {
  private final AtomicReference<ServerConfiguration> current = new AtomicReference<>();
  private final CountDownLatch ready = new CountDownLatch(1);

  public ServerConfiguration get() {
    return current.get();
  }

  public void set(ServerConfiguration configuration) {
    current.set(configuration);
  }

  public void markReady() {
    ready.countDown();
  }

  public boolean wasReady() {
    return ready.getCount() == 0;
  }

  public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
    return ready.await(timeout, unit);
  }
}
