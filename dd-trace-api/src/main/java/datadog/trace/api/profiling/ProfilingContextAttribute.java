package datadog.trace.api.profiling;

public interface ProfilingContextAttribute {

  final class NoOp implements ProfilingContextAttribute {

    public static final NoOp INSTANCE = new NoOp();
  }
}
