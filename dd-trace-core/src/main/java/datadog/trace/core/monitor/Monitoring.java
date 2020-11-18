package datadog.trace.core.monitor;

import static datadog.trace.api.Platform.isJavaVersionAtLeast;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.StatsDClient;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;

public final class Monitoring {

  public static final Monitoring DISABLED = new Monitoring();

  private final StatsDClient statsd;
  private final long flushAfterNanos;
  private final boolean enabled;

  public Monitoring(final StatsDClient statsd, long flushInterval, TimeUnit flushUnit) {
    this.statsd = statsd;
    this.flushAfterNanos = flushUnit.toNanos(flushInterval);
    this.enabled = true;
  }

  private Monitoring() {
    this.statsd = new NoOpStatsDClient();
    this.flushAfterNanos = 0;
    this.enabled = false;
  }

  public Recording newTimer(final String name) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
      return NoOpRecording.NO_OP;
    }
    DDSketch sketch = SketchFactory.createHistogram();
    return null == sketch ? NoOpRecording.NO_OP : new Timer(name, sketch, statsd, flushAfterNanos);
  }

  public Recording newTimer(final String name, final String... tags) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
      return NoOpRecording.NO_OP;
    }
    DDSketch sketch = SketchFactory.createHistogram();
    return null == sketch
        ? NoOpRecording.NO_OP
        : new Timer(name, sketch, tags, statsd, flushAfterNanos);
  }

  public Recording newThreadLocalTimer(final String name) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
      return NoOpRecording.NO_OP;
    }
    return new ThreadLocalRecording(
        new ThreadLocal<Recording>() {
          @Override
          protected Recording initialValue() {
            return newTimer(name, "thread:" + Thread.currentThread().getName());
          }
        });
  }

  public Recording newCPUTimer(final String name) {
    if (!enabled || !isJavaVersionAtLeast(8)) {
      return NoOpRecording.NO_OP;
    }
    DDSketch sketch = SketchFactory.createHistogram();
    return null == sketch
        ? NoOpRecording.NO_OP
        : new CPUTimer(name, sketch, statsd, flushAfterNanos);
  }

  public Counter newCounter(final String name) {
    if (!enabled) {
      return NoOpCounter.NO_OP;
    }
    return new StatsDCounter(name, statsd);
  }

  private static class SketchFactory {
    static DDSketch createHistogram() {
      try {
        return (DDSketch)
            DDSketch.class
                .getDeclaredMethod("fastCollapsingLowest", double.class, int.class)
                .invoke(null, 0.01, 1024);
      } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
        return null;
      }
    }
  }
}
