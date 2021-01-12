package datadog.trace.test.util;

public class ForkedTestUtils {
  public static String getMaxMemoryArgumentForFork() {
    return "-Xmx" + System.getProperty("datadog.forkedMaxHeapSize", "512M");
  }

  public static String getMinMemoryArgumentForFork() {
    return "-Xms" + System.getProperty("datadog.forkedMinHeapSize", "64M");
  }
}
