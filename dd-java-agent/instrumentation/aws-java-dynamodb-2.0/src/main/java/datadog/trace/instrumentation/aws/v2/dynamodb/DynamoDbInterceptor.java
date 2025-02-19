package datadog.trace.instrumentation.aws.v2.dynamodb;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

public class DynamoDbInterceptor implements ExecutionInterceptor {
  private static final Logger log = LoggerFactory.getLogger(DynamoDbInterceptor.class);

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogSpan", () -> new ExecutionAttribute<>("DatadogSpan"));

  private static final boolean CAN_ADD_SPAN_POINTERS = Config.get().isAddSpanPointers("aws");

  @Override
  public void afterExecution(
      Context.AfterExecution context, ExecutionAttributes executionAttributes) {
    if (!CAN_ADD_SPAN_POINTERS) {
      return;
    }

    System.out.println("[tracer] DynamoDB instrumentation");
  }
}
