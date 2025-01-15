package datadog.trace.instrumentation.aws.v2.s3;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class S3Interceptor implements ExecutionInterceptor {
  private static final Logger log = LoggerFactory.getLogger(S3Interceptor.class);

  public static final ExecutionAttribute<AgentSpan> SPAN_ATTRIBUTE =
      InstanceStore.of(ExecutionAttribute.class)
          .putIfAbsent("DatadogSpan", () -> new ExecutionAttribute<>("DatadogSpan"));

  @Override
  public void afterExecution(
      Context.AfterExecution context, ExecutionAttributes executionAttributes) {
    if (!Config.get().isSpanPointersEnabled()) {
      return;
    }

    AgentSpan span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
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
    span.setTag("s3.eTag", eTag);
  }
}
