package datadog.trace.bootstrap;

import de.thetaphi.forbiddenapis.SuppressForbidden;

/** Minimal application used to mark the point where agent startup reaches application main. */
public final class ClassDataBenchmarkTarget {
  static final String READY = "CLASSDATA_BENCHMARK_READY";

  private ClassDataBenchmarkTarget() {}

  @SuppressForbidden // The parent benchmark waits for this readiness marker on stdout.
  public static void main(String[] args) {
    System.out.println(READY);
  }
}
