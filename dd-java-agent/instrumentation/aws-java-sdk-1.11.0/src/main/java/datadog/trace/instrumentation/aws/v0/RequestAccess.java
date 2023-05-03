package datadog.trace.instrumentation.aws.v0;

import datadog.trace.api.GenericClassValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;

final class RequestAccess {
  private static final ClassValue<RequestAccess> REQUEST_ACCESS =
      GenericClassValue.of(
          // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
          new Function<Class<?>, RequestAccess>() {
            @Override
            public RequestAccess apply(final Class<?> requestType) {
              return new RequestAccess(requestType);
            }
          });

  static RequestAccess of(final Object request) {
    return REQUEST_ACCESS.get(request.getClass());
  }

  private final MethodHandle getBucketName;
  private final MethodHandle getQueueUrl;
  private final MethodHandle getQueueName;
  private final MethodHandle getTopicArn;
  private final MethodHandle getStreamName;
  private final MethodHandle getTableName;

  private RequestAccess(final Class<?> requestType) {
    getBucketName = findGetter(requestType, "getBucketName");
    getQueueUrl = findGetter(requestType, "getQueueUrl");
    getQueueName = findGetter(requestType, "getQueueName");
    getTopicArn = findGetter(requestType, "getTopicArn");
    getStreamName = findGetter(requestType, "getStreamName");
    getTableName = findGetter(requestType, "getTableName");
  }

  String getBucketName(final Object request) {
    return invoke(getBucketName, request);
  }

  String getQueueUrl(final Object request) {
    return invoke(getQueueUrl, request);
  }

  String getQueueName(final Object request) {
    return invoke(getQueueName, request);
  }

  String getTopicArn(final Object request) {
    return invoke(getTopicArn, request);
  }

  String getStreamName(final Object request) {
    return invoke(getStreamName, request);
  }

  String getTableName(final Object request) {
    return invoke(getTableName, request);
  }

  private static String invoke(final MethodHandle method, final Object request) {
    if (null == method) {
      return null;
    }
    try {
      return (String) method.invoke(request);
    } catch (Throwable e) {
      return null;
    }
  }

  private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();
  private static final MethodType STRING_RETURN_TYPE = MethodType.methodType(String.class);

  private static MethodHandle findGetter(final Class<?> requestType, final String methodName) {
    try {
      return PUBLIC_LOOKUP.findVirtual(requestType, methodName, STRING_RETURN_TYPE);
    } catch (Throwable e) {
      return null;
    }
  }
}
