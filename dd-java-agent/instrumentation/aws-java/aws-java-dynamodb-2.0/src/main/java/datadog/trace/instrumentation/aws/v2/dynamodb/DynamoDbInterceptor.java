package datadog.trace.instrumentation.aws.v2.dynamodb;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context.AfterExecution;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

public class DynamoDbInterceptor implements ExecutionInterceptor {
  private static final Logger log = LoggerFactory.getLogger(DynamoDbInterceptor.class);

  public static final ExecutionAttribute<Context> CONTEXT_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogContext", () -> new ExecutionAttribute<>("DatadogContext"));

  private static final boolean CAN_ADD_SPAN_POINTERS = Config.get().isAddSpanPointers("aws");

  @Override
  public void afterExecution(AfterExecution context, ExecutionAttributes executionAttributes) {
    if (!CAN_ADD_SPAN_POINTERS) {
      return;
    }

    Context ddContext = executionAttributes.getAttribute(CONTEXT_ATTRIBUTE);
    AgentSpan span = fromContext(ddContext);
    if (span == null) {
      log.debug("Unable to find DynamoDb request span. Not creating span pointer.");
      return;
    }

    SdkRequest request = context.request();
    String tableName = request.getValueForField("TableName", String.class).orElse(null);
    Map<String, AttributeValue> keys = null;

    if (request instanceof UpdateItemRequest) {
      keys = ((UpdateItemRequest) request).key();
    } else if (request instanceof DeleteItemRequest) {
      keys = ((DeleteItemRequest) request).key();
    }

    if (keys != null) {
      DynamoDbUtil.addSpanPointer(span, tableName, keys);
    }
  }
}
