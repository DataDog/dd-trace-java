package datadog.trace.instrumentation.aws.v2.s3;

import static datadog.trace.bootstrap.instrumentation.spanpointers.SpanPointersHelper.S3_PTR_KIND;
import static datadog.trace.bootstrap.instrumentation.spanpointers.SpanPointersHelper.addSpanPointer;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstanceStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
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

    String bucket, key, eTag;
    Object request = context.request();
    Object response = context.response();

    // Get bucket, key, and eTag for hash calculation.
    // https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/s3/S3Client.html
    if (request instanceof PutObjectRequest) {
      PutObjectRequest putObjectRequest = (PutObjectRequest) request;
      bucket = putObjectRequest.bucket();
      key = putObjectRequest.key();
      eTag = ((PutObjectResponse) response).eTag();
    } else if (request instanceof CopyObjectRequest) {
      CopyObjectRequest copyObjectRequest = (CopyObjectRequest) request;
      bucket = copyObjectRequest.destinationBucket();
      key = copyObjectRequest.destinationKey();
      eTag = ((CopyObjectResponse) response).copyObjectResult().eTag();
    } else if (request instanceof CompleteMultipartUploadRequest) {
      CompleteMultipartUploadRequest completeMultipartUploadRequest =
          (CompleteMultipartUploadRequest) request;
      bucket = completeMultipartUploadRequest.bucket();
      key = completeMultipartUploadRequest.key();
      eTag = ((CompleteMultipartUploadResponse) response).eTag();
    } else {
      return;
    }

    // Hash calculation rules:
    // https://github.com/DataDog/dd-span-pointer-rules/blob/main/AWS/S3/Object/README.md
    if (eTag != null
        && !eTag.isEmpty()
        && eTag.charAt(0) == '"'
        && eTag.charAt(eTag.length() - 1) == '"') {
      eTag = eTag.substring(1, eTag.length() - 1);
    }
    String[] components = new String[] {bucket, key, eTag};
    try {
      addSpanPointer(span, S3_PTR_KIND, components);
    } catch (Exception e) {
      log.debug("Failed to add span pointer: {}", e.getMessage());
    }
  }
}
