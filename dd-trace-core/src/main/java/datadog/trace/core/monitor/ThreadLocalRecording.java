package datadog.trace.core.monitor;

import datadog.communication.monitor.Recording;

public final class ThreadLocalRecording extends Recording {

  private final ThreadLocal<Recording> tls;

  public ThreadLocalRecording(ThreadLocal<Recording> tls) {
    this.tls = tls;
  }

  @Override
  public Recording start() {
    return tls.get().start();
  }

  @Override
  public void reset() {
    tls.get().reset();
  }

  @Override
  public void stop() {
    tls.get().stop();
  }

  @Override
  public void flush() {
    tls.get().flush();
  }
}
