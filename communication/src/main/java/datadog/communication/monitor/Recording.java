package datadog.communication.monitor;

public abstract class Recording implements AutoCloseable {
  @Override
  public void close() {
    stop();
  }

  public abstract Recording start();

  public abstract void reset();

  public abstract void stop();

  public abstract void flush();
}
