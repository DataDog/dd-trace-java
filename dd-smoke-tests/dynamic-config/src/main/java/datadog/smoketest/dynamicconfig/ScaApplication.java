package datadog.smoketest.dynamicconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;

/**
 * Simple test application for SCA smoke tests.
 *
 * <p>This application uses Jackson's ObjectMapper which will be instrumented by SCA to detect
 * vulnerable method invocations.
 */
public class ScaApplication {

  public static final long TIMEOUT_IN_SECONDS = 15;

  public static void main(String[] args) throws Exception {
    // Load a class that could be targeted by SCA instrumentation
    // This ensures the class is loaded and available for retransformation
    ObjectMapper mapper = new ObjectMapper();

    System.out.println("ScaApplication started with ObjectMapper: " + mapper.getClass().getName());
    System.out.println("READY_FOR_INSTRUMENTATION");

    // Wait for Remote Config to send SCA configuration and apply instrumentation
    System.out.println("Waiting for SCA configuration...");
    Thread.sleep(TimeUnit.SECONDS.toMillis(5));

    // Now invoke the target method that should be instrumented
    System.out.println("INVOKING_TARGET_METHOD");
    try {
      // This should trigger SCA detection if instrumentation is working
      String json = "{\"name\":\"test\"}";
      mapper.readValue(json, Object.class);
      System.out.println("METHOD_INVOCATION_DONE");
    } catch (Exception e) {
      System.err.println("Error invoking target method: " + e.getMessage());
      e.printStackTrace();
    }

    // Wait a bit more to allow logs to be flushed
    Thread.sleep(TimeUnit.SECONDS.toMillis(2));

    System.out.println("ScaApplication finished");
    System.exit(0);
  }
}
