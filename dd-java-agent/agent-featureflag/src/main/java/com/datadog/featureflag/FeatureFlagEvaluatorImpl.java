package com.datadog.featureflag;

import com.datadog.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.FeatureFlagEvaluator;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class FeatureFlagEvaluatorImpl
    implements FeatureFlagEvaluator, Consumer<ServerConfiguration> {

  private final Set<Listener> listeners = Collections.newSetFromMap(new WeakHashMap<>());
  private final AtomicReference<ServerConfiguration> configuration = new AtomicReference<>();

  @Override
  public Resolution<Boolean> evaluate(
      final String key, final Boolean defaultValue, final Context context) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public Resolution<Integer> evaluate(
      final String key, final Integer defaultValue, final Context context) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public Resolution<Double> evaluate(
      final String key, final Double defaultValue, final Context context) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public Resolution<String> evaluate(
      final String key, final String defaultValue, final Context context) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public Resolution<Object> evaluate(
      final String key, final Object defaultValue, final Context context) {
    // TODO
    throw new UnsupportedOperationException();
  }

  @Override
  public void addListener(final Listener listener) {
    if (configuration.get() != null) {
      listener.onInitialized();
    }
    synchronized (listeners) {
      this.listeners.add(listener);
    }
  }

  @Override
  public void accept(final ServerConfiguration serverConfiguration) {
    final boolean init = configuration.getAndSet(serverConfiguration) == null;
    synchronized (listeners) {
      if (init) {
        listeners.forEach(Listener::onInitialized);
      } else {
        listeners.forEach(Listener::onConfigurationChanged);
      }
    }
  }
}
