package datadog.trace.bootstrap.instrumentation.spark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkAgentContext {

  public interface SparkAgentIntf {
    void register(ClassLoader sparkClassLoader);
  }

  private static final Logger log = LoggerFactory.getLogger(SparkAgentContext.class);

  private static SparkAgentIntf sparkAgent;

  public static void init(SparkAgentIntf sparkAgent) {
    SparkAgentContext.sparkAgent = sparkAgent;
  }

  public static void register(ClassLoader sparkClassLoader) {
    log.info("SparkAgentContext.register");
    sparkAgent.register(sparkClassLoader);
  }
}
