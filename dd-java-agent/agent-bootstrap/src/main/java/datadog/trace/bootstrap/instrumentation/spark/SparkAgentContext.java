package datadog.trace.bootstrap.instrumentation.spark;

public class SparkAgentContext {

  public interface SparkAgentIntf {
    void sendMetric(long value); // FIXME

    void register(ClassLoader sparkClassLoader);
  }

  private static SparkAgentIntf sparkAgent;

  public static void init(SparkAgentIntf sparkAgent) {
    SparkAgentContext.sparkAgent = sparkAgent;
  }

  public static void register(ClassLoader sparkClassLoader) {
    System.err.println("SparkAgentContext.register");
    sparkAgent.register(sparkClassLoader);
  }

  public static void sendMetrics(long value) {
    sparkAgent.sendMetric(value);
  }
}
