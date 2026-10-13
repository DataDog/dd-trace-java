package datadog.smoketest.concurrent;

public class VirtualThreadStartup {
  public static void main(String[] args) throws InterruptedException {
    boolean[] ran = {false};
    Thread thread = Thread.startVirtualThread(() -> ran[0] = true);
    thread.join();
    if (!ran[0]) {
      throw new AssertionError("Virtual thread did not execute");
    }
  }
}
