package datadog.trace.api.experimental;

public interface ProfilingContextSetter {
  /**
   * Sets the context value on the current thread
   *
   * @param value the value to set
   */
  void set(CharSequence value);

  /** Clears context for the current thread */
  void clear();

  final class NoOp implements ProfilingContextSetter {

    public static final NoOp INSTANCE = new NoOp();

    @Override
    public void set(CharSequence value) {}

    @Override
    public void clear() {}
  }
}
