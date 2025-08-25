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
    if (request instanceof UpdateItemRequest) {
      Map<String, AttributeValue> keys = ((UpdateItemRequest) request).key();
      DynamoDbUtil.exportTagsWithKnownKeys(span, keys);
    } else if (request instanceof DeleteItemRequest) {
      Map<String, AttributeValue> keys = ((DeleteItemRequest) request).key();
      DynamoDbUtil.exportTagsWithKnownKeys(span, keys);
    }
  }
}
