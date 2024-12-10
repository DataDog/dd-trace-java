package datadog.trace.instrumentation.spark;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import org.apache.spark.SparkContext;

public class SparkSessionUtils {
  public static SparkSessionUtils SESSION_UTILS = new SparkSessionUtils();

  public void updatePreferredServiceName(SparkContext context) {
    // we are not using `updatePreferredServiceName` here, since it will
    // update service names for all spans.
    AgentTracer.get()
        .getDataStreamsMonitoring()
        .setPreferredServiceName(SparkConfUtils.getServiceNameOverride(context.conf()));
  }
}
