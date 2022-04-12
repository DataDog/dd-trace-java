package datadog.trace.api.profiling;

final class TestTracingContextTracker implements TracingContextTracker {
  @Override
  public boolean release() {
    return false;
  }

  @Override
  public void activateContext() {}

  @Override
  public void deactivateContext() {}

  @Override
  public void maybeDeactivateContext() {}

  @Override
  public byte[] persist() {
    return new byte[0];
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Override
  public DelayedTracker asDelayed() {
    return null;
  }
}
