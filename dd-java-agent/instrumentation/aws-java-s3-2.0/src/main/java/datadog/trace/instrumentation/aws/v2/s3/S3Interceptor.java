package datadog.trace.instrumentation.aws.v2.s3;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

public class S3Interceptor implements ExecutionInterceptor {
  @Override
  public void afterExecution(
      Context.AfterExecution context, ExecutionAttributes executionAttributes) {
    System.out.println("S3Interceptor afterExecution()");
  }
}
