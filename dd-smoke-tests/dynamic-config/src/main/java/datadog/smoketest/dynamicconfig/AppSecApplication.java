package datadog.smoketest.dynamicconfig;

import java.util.concurrent.TimeUnit;

public class AppSecApplication {

  public static final long TIMEOUT_IN_SECONDS = 15;

  public static void main(String[] args) throws InterruptedException {
    // just wait as we want to test RC payloads
    Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_IN_SECONDS));
    System.exit(0);
  }
}
