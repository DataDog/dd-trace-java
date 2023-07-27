package datadog.trace.instrumentation.aws.v2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;

public class AwsExecutionAttribute {
  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      new ExecutionAttribute<>("DatadogSpan");

}
