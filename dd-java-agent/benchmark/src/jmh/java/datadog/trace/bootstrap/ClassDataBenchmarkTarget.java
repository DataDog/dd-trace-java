package datadog.trace.bootstrap;

/** Minimal application used to mark the point where agent startup reaches application main. */
public final class ClassDataBenchmarkTarget {
  static final String READY = "CLASSDATA_BENCHMARK_READY";

  private ClassDataBenchmarkTarget() {}

  public static void main(String[] args) {
    System.out.println(READY);
  }
}
