package datadog.trace.instrumentation.aws.v2.s3;

import static datadog.trace.bootstrap.instrumentation.spanpointers.SpanPointersHelper.generatePointerHash;

import java.security.NoSuchAlgorithmException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

public class S3Interceptor implements ExecutionInterceptor {
  @Override
  public void afterExecution(
      Context.AfterExecution context, ExecutionAttributes executionAttributes) {
    String bucket, key, eTag;
    Object request = context.request();
    Object response = context.response();
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

    if (eTag.startsWith("\"") && eTag.endsWith("\"")) {
      eTag = eTag.substring(1, eTag.length() - 1);
    }
    System.out.printf("[DEBUG] bucket: %s, key: %s, eTag: %s%n", bucket, key, eTag);
    try {
      String hash = generatePointerHash(new String[] {bucket, key, eTag});
      System.out.println("[DEBUG] hash: " + hash);
    } catch (NoSuchAlgorithmException ignored) {
    }
  }
}
