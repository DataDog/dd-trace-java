package datadog.smoketest.dynamicconfig;

import java.util.concurrent.TimeUnit;

/**
 * Simple test application for SCA smoke tests.
 *
 * <p>This application uses Jackson's ObjectMapper which will be instrumented by SCA to detect
 * vulnerable method invocations.
 */
public class ScaApplication {

  public static final long TIMEOUT_IN_SECONDS = 15;

  public static void main(String[] args) throws InterruptedException {
    // Load a class that could be targeted by SCA instrumentation
    // This ensures the class is loaded and available for retransformation
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    System.out.println("ScaApplication started with ObjectMapper: " + mapper.getClass().getName());

    // Wait for Remote Config to send SCA configuration
    Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_IN_SECONDS));

    System.exit(0);
  }
}
