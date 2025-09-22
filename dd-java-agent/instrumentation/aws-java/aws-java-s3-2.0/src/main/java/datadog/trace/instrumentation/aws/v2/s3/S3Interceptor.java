package datadog.trace.instrumentation.aws.v2.s3;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.S3_ETAG;

import datadog.context.Context;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.interceptor.Context.AfterExecution;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class S3Interceptor implements ExecutionInterceptor {
  private static final Logger log = LoggerFactory.getLogger(S3Interceptor.class);

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
    AgentSpan span = AgentSpan.fromContext(ddContext);
    if (span == null) {
      log.debug("Unable to find S3 request span. Not creating span pointer.");
      return;
    }
    String eTag;
    Object response = context.response();

    // Get eTag for hash calculation.
    // https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html
    if (response instanceof PutObjectResponse) {
      eTag = ((PutObjectResponse) response).eTag();
    } else if (response instanceof CopyObjectResponse) {
      eTag = ((CopyObjectResponse) response).copyObjectResult().eTag();
    } else if (response instanceof CompleteMultipartUploadResponse) {
      eTag = ((CompleteMultipartUploadResponse) response).eTag();
    } else {
      return;
    }

    // Store eTag as tag, then calculate hash + add span pointers in SpanPointersProcessor.
    // Bucket and key are already stored as tags in AwsSdkClientDecorator, so need to make redundant
    // tags.
    span.setTag(S3_ETAG, eTag);
  }
}
