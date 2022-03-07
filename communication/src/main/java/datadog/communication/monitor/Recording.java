package datadog.communication.monitor;

public abstract class Recording implements AutoCloseable {
  @Override
  public void close() {
    stop();
  }

  public abstract Recording start();

  public abstract void reset();

  public abstract void stop();

  /**
   * Manually add a measurement. Never flushes!
   *
   * @param durationInNanos
   */
  public abstract void addMeasurement(long durationInNanos);

  public abstract void flush();
}
