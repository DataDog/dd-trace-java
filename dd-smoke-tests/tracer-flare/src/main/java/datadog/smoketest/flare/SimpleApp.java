package datadog.smoketest.flare;

public class SimpleApp {
  public static void main(String[] args) throws InterruptedException {
    System.out.println("SimpleApp starting - waiting for flare generation");

    // Keep the app running indefinitely
    // The flare will be triggered after 10 seconds (configured in test)
    // The test will wait for the flare and then terminate the process
    while (true) {
      System.out.println("SimpleApp running...");
      Thread.sleep(5000);
    }
  }
}
