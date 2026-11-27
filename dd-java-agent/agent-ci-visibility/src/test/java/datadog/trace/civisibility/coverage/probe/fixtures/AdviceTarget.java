package datadog.trace.civisibility.coverage.probe.fixtures;

/**
 * Code that runs from inside JDK methods, as a mocking library's advice does once the library
 * instruments a JDK class.
 */
public final class AdviceTarget implements Runnable {

  private int runs;

  @Override
  public void run() {
    runs++;
  }
}
