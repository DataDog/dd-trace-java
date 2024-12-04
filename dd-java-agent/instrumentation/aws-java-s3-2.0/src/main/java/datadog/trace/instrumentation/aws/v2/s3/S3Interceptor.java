package datadog.trace.instrumentation.aws.v2.s3;

import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3Interceptor implements ExecutionInterceptor {
  @Override
  public SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
    if (context.request() instanceof PutObjectRequest) {
      System.out.println("[DEBUG] PutObjectRequest");
    } else if (context.request() instanceof CopyObjectRequest) {
      System.out.println("[DEBUG] CopyObjectRequest");
    } else if (context.request() instanceof CompleteMultipartUploadRequest) {
      System.out.println("[DEBUG] CompleteMultipartUploadRequest");
    } else {
      System.out.println("[DEBUG] unknown request");
    }

    return context.request();
  }
}
