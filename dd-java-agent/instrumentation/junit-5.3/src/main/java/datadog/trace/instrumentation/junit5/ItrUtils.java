package datadog.trace.instrumentation.junit5;

import datadog.trace.util.Strings;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * A smaller subset of utility methods that have to be available both in the platform and the engine
 * classloaders
 */
public class ItrUtils {

  public static final String SPOCK_ENGINE_ID = "spock";

  public static String getParameters(MethodSource methodSource, String displayName) {
    if (methodSource.getMethodParameterTypes() == null
        || methodSource.getMethodParameterTypes().isEmpty()) {
      return null;
    }
    return "{\"metadata\":{\"test_name\":\"" + Strings.escapeToJson(displayName) + "\"}}";
  }

  public static String getTestName(
      MethodSource methodSource, String displayName, String testEngineId) {
    return SPOCK_ENGINE_ID.equals(testEngineId) ? displayName : methodSource.getMethodName();
  }
}
