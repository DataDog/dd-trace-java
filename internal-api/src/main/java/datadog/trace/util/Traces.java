package datadog.trace.util;

import static datadog.trace.util.Strings.truncate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Traces {

  private static final int MAX_TYPE_LEN = 100;
  private static final int MAX_SERVICE_LEN = 100;
  private static final int MAX_OP_NAME_LEN = 100;

  private static final String DEFAULT_SERVICE_NAME = "unnamed-service";
  private static final String DEFAULT_OPERATION_NAME = "unnamed_operation";

  private static final Logger log = LoggerFactory.getLogger(Traces.class);

  public static String normalizeServiceName(final String service) {
    if (service == null || service.isEmpty()) {
      log.debug(
          "Fixing malformed trace. Service  is empty (reason:service_empty), setting span.service={}.",
          service);
      return DEFAULT_SERVICE_NAME;
    }

    String svc = service;
    if (svc.length() > MAX_SERVICE_LEN) {
      log.debug(
          "Fixing malformed trace. Service is too long (reason:service_truncate), truncating span.service to length={}.",
          MAX_SERVICE_LEN);
      svc = truncate(svc, MAX_SERVICE_LEN);
    }

    return normalizeTag(svc);
  }

  public static CharSequence normalizeOperationName(final CharSequence opName) {
    if (opName == null || opName.length() == 0) {
      return DEFAULT_OPERATION_NAME;
    }

    CharSequence name = opName;
    if (name.length() > MAX_OP_NAME_LEN) {
      log.debug(
          "Fixing malformed trace. Name is too long (reason:span_name_truncate), truncating span.name to length={}.",
          MAX_OP_NAME_LEN);
      name = truncate(name, MAX_OP_NAME_LEN);
    }

    name = normalizeMetricName(name, MAX_OP_NAME_LEN);
    if (name == null) {
      name = DEFAULT_OPERATION_NAME;
    }
    return name;
  }

  public static String normalizeSpanType(final String spanType) {
    if (spanType != null && spanType.length() > MAX_TYPE_LEN) {
      log.debug(
          "Fixing malformed trace. Type is too long (reason:type_truncate), truncating span.type to length={}",
          MAX_TYPE_LEN);
      return truncate(spanType, MAX_TYPE_LEN);
    }
    return spanType;
  }

  public static String normalizeTag(final String tag) {

    return null;
  }

  public static CharSequence normalizeMetricName(final CharSequence name, int limit) {
    return null;
  }

  public static boolean isValidStatusCode(final String httpStatusCode) {
    if (httpStatusCode == null || httpStatusCode.isEmpty()) {
      return false;
    }
    try {
      final int code = Integer.parseInt(httpStatusCode);
      return (code >= 100 && code < 600);
    } catch (NumberFormatException ex) {
      return false;
    }
  }
}
