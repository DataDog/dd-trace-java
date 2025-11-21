package datadog.trace.api.featureflag;

import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public abstract class FeatureFlaggingGateway {

  public interface ConfigListener extends Consumer<ServerConfiguration> {}

  public interface ExposureListener extends Consumer<ExposureEvent> {}

  private static final List<ConfigListener> CONFIG_LISTENERS = new CopyOnWriteArrayList<>();
  private static final List<ExposureListener> EXPOSURE_LISTENERS = new CopyOnWriteArrayList<>();

  private static final AtomicReference<ServerConfiguration> CURRENT_CONFIG =
      new AtomicReference<>();

  private FeatureFlaggingGateway() {}

  public static void addConfigListener(final ConfigListener listener) {
    CONFIG_LISTENERS.add(listener);
    final ServerConfiguration current = CURRENT_CONFIG.get();
    if (current != null) {
      listener.accept(current);
    }
  }

  public static void removeConfigListener(final ConfigListener listener) {
    CONFIG_LISTENERS.remove(listener);
  }

  public static void dispatch(final ServerConfiguration config) {
    CURRENT_CONFIG.set(config);
    CONFIG_LISTENERS.forEach(listener -> listener.accept(config));
  }

  public static void addExposureListener(final ExposureListener listener) {
    EXPOSURE_LISTENERS.add(listener);
  }

  public static void removeExposureListener(final ExposureListener listener) {
    EXPOSURE_LISTENERS.remove(listener);
  }

  public static void dispatch(final ExposureEvent event) {
    EXPOSURE_LISTENERS.forEach(listener -> listener.accept(event));
  }
}
