package datadog.openfeature.internal.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Thread-safe last-known-good configuration state. */
public final class ConfigurationStore implements ConfigurationSink {

  private final UfcParser parser;
  private final AtomicReference<ConfigurationSnapshot> current = new AtomicReference<>();
  private final List<Consumer<ConfigurationSnapshot>> listeners = new CopyOnWriteArrayList<>();
  private final Object changeMonitor = new Object();

  public ConfigurationStore() {
    this(new UfcParser());
  }

  ConfigurationStore(final UfcParser parser) {
    this.parser = parser;
  }

  @Override
  public ApplyResult apply(final byte[] content) {
    final ConfigurationSnapshot next;
    try {
      next = parser.parse(content);
    } catch (final IOException | RuntimeException ignored) {
      return ApplyResult.REJECTED;
    }
    current.set(next);
    signalChange(next);
    return ApplyResult.ACCEPTED;
  }

  @Override
  public ApplyResult clear() {
    current.set(null);
    signalChange(null);
    return ApplyResult.CLEARED;
  }

  public ConfigurationSnapshot current() {
    return current.get();
  }

  public boolean hasConfiguration() {
    return current.get() != null;
  }

  public void addListener(final Consumer<ConfigurationSnapshot> listener) {
    listeners.add(listener);
    final ConfigurationSnapshot snapshot = current.get();
    if (snapshot != null) {
      listener.accept(snapshot);
    }
  }

  public void removeListener(final Consumer<ConfigurationSnapshot> listener) {
    listeners.remove(listener);
  }

  public boolean awaitConfiguration(final long timeout, final TimeUnit unit)
      throws InterruptedException {
    if (hasConfiguration()) {
      return true;
    }
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    synchronized (changeMonitor) {
      while (!hasConfiguration()) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(changeMonitor, remaining);
      }
      return true;
    }
  }

  @SuppressFBWarnings(
      value = "NN_NAKED_NOTIFY",
      justification = "The caller updates the atomic snapshot before it signals waiting readers")
  private void signalChange(final ConfigurationSnapshot snapshot) {
    synchronized (changeMonitor) {
      changeMonitor.notifyAll();
    }
    for (final Consumer<ConfigurationSnapshot> listener : listeners) {
      listener.accept(snapshot);
    }
  }
}
