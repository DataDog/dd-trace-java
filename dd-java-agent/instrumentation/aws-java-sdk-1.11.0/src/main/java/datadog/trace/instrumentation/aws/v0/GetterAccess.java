package datadog.trace.instrumentation.aws.v0;

import datadog.trace.api.GenericClassValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

final class GetterAccess {
  private static final ClassValue<GetterAccess> GETTER_ACCESS =
      GenericClassValue.of(
          // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
          new Function<Class<?>, GetterAccess>() {
            @Override
            public GetterAccess apply(final Class<?> requestType) {
              return new GetterAccess(requestType);
            }
          });

  static GetterAccess of(final Object request) {
    return GETTER_ACCESS.get(request.getClass());
  }

  private static final Pattern REQUEST_OPERATION_NAME_PATTERN =
      Pattern.compile("Request", Pattern.LITERAL);

  private final String operationName;
  private final MethodHandle getKey;
  private final MethodHandle getBucketName;
  private final MethodHandle getQueueUrl;
  private final MethodHandle getQueueName;
  private final MethodHandle getTopicArn;
  private final MethodHandle getStreamName;
  private final MethodHandle getStreamARN;
  private final MethodHandle getRecords;
  private final MethodHandle getPublishBatchRequestEntries;
  private final MethodHandle getApproximateArrivalTimestamp;
  private final MethodHandle getTableName;

  private GetterAccess(final Class<?> objectType) {
    operationName =
        REQUEST_OPERATION_NAME_PATTERN.matcher(objectType.getSimpleName()).replaceAll("");
    getKey = findStringGetter(objectType, "getKey");
    getBucketName = findStringGetter(objectType, "getBucketName");
    getQueueUrl = findStringGetter(objectType, "getQueueUrl");
    getQueueName = findStringGetter(objectType, "getQueueName");
    getTopicArn = findStringGetter(objectType, "getTopicArn");
    getStreamName = findStringGetter(objectType, "getStreamName");
    getStreamARN = findStringGetter(objectType, "getStreamARN");
    getRecords = findListGetter(objectType, "getRecords");
    getPublishBatchRequestEntries = findListGetter(objectType, "getPublishBatchRequestEntries");
    getApproximateArrivalTimestamp =
        findGetter(objectType, "getApproximateArrivalTimestamp", Date.class);
    getTableName = findStringGetter(objectType, "getTableName");
  }

  String getOperationNameFromType() {
    return operationName;
  }

  String getKey(final Object object) {
    return invokeForString(getKey, object);
  }

  String getBucketName(final Object object) {
    return invokeForString(getBucketName, object);
  }

  String getQueueUrl(final Object object) {
    return invokeForString(getQueueUrl, object);
  }

  String getQueueName(final Object object) {
    return invokeForString(getQueueName, object);
  }

  String getTopicArn(final Object object) {
    return invokeForString(getTopicArn, object);
  }

  String getStreamName(final Object object) {
    return invokeForString(getStreamName, object);
  }

  String getStreamARN(final Object object) {
    return invokeForString(getStreamARN, object);
  }

  List getRecords(final Object object) {
    return invokeForList(getRecords, object);
  }

  List getEntries(final Object object) {
    return invokeForList(getPublishBatchRequestEntries, object);
  }

  String getTableName(final Object object) {
    return invokeForString(getTableName, object);
  }

  Date getApproximateArrivalTimestamp(final Object object) {
    return invoke(getApproximateArrivalTimestamp, object);
  }

  private static String invokeForString(final MethodHandle method, final Object object) {
    if (null == method) {
      return null;
    }
    try {
      return (String) method.invoke(object);
    } catch (Throwable e) {
      return null;
    }
  }

  private static List invokeForList(final MethodHandle method, final Object object) {
    if (null == method) {
      return null;
    }
    try {
      return (List) method.invoke(object);
    } catch (Throwable e) {
      return null;
    }
  }

  private static <T> T invoke(final MethodHandle method, final Object object) {
    if (null == method) {
      return null;
    }
    try {
      return (T) method.invoke(object);
    } catch (Throwable e) {
      return null;
    }
  }

  private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();
  private static final MethodType STRING_RETURN_TYPE = MethodType.methodType(String.class);
  private static final MethodType LIST_RETURN_TYPE = MethodType.methodType(List.class);

  private static MethodHandle findStringGetter(
      final Class<?> requestType, final String methodName) {
    try {
      return PUBLIC_LOOKUP.findVirtual(requestType, methodName, STRING_RETURN_TYPE);
    } catch (Throwable e) {
      return null;
    }
  }

  private static MethodHandle findListGetter(final Class<?> requestType, final String methodName) {
    try {
      return PUBLIC_LOOKUP.findVirtual(requestType, methodName, LIST_RETURN_TYPE);
    } catch (Throwable e) {
      return null;
    }
  }

  private static MethodHandle findGetter(
      final Class<?> pojoType, final String methodName, final Class<?> returnType) {
    try {
      return PUBLIC_LOOKUP.findVirtual(pojoType, methodName, MethodType.methodType(returnType));
    } catch (Throwable e) {
      return null;
    }
  }
}
