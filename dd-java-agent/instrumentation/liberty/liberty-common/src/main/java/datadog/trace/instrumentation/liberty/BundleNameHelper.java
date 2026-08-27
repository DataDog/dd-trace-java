package datadog.trace.instrumentation.liberty;

public class BundleNameHelper {
  private BundleNameHelper() {}

  public static String extractDeploymentName(final String id) {
    // id is something like <type>:name#somethingelse
    final int head = id.indexOf(':');
    if (head < 0) {
      return null;
    }
    final int tail = id.lastIndexOf('#');
    if (tail <= head) {
      return null;
    }
    return id.substring(head + 1, tail);
  }
}
