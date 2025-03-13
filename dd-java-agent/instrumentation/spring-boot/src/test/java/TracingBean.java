import datadog.trace.api.Trace;

public class TracingBean {
  @Trace
  public static void test() {}
}
