package datadog.trace.agent.tooling.bytebuddy.csi;

public class CallSiteTypeBenchmarkHelper {

  public static void before(final StringBuilder self, final int index, final char[] str) {
    self.append(" [Transformed]");
  }

  public static StringBuilder around(final StringBuilder self, final int index, final char[] str) {
    self.append(" [Transformed]");
    return self.insert(index, str);
  }

  public static StringBuilder after(
      final StringBuilder self, final int index, final char[] str, final StringBuilder result) {
    result.append(" [Transformed]");
    return result;
  }
}
