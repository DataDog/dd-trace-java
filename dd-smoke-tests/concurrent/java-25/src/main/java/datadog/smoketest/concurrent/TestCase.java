package datadog.smoketest.concurrent;

@FunctionalInterface
public interface TestCase {
  void run() throws InterruptedException;
}
