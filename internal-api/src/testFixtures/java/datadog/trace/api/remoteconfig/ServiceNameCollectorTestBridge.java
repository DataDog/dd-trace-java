package datadog.trace.api.remoteconfig;

/**
 * Bridge class to allow tests to access package-private method exposed by the {@code
 * ServiceNameCollector}
 */
public class ServiceNameCollectorTestBridge {
  public static void setInstance(ServiceNameCollector instance) {
    ServiceNameCollector.setInstance(instance);
  }
}
