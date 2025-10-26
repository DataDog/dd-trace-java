package datadog.trace.util;

import static datadog.trace.util.Strings.truncate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods to normalize trace data. This normalization is recommended if the trace is sent
 * to a public HTTP intake directly instead of using a Datadog Agent. The normalization methods try
 * to mimic the normalization is done in the Datadog Agent.
 */
public class TraceUtils {

  private static final int MAX_TYPE_LEN = 100;
  private static final int MAX_SERVICE_LEN = 100;
  private static final int MAX_OP_NAME_LEN = 100;
  private static final int MAX_ENV_LEN = 200;

  static final String DEFAULT_SERVICE_NAME = "unnamed-service";
  static final String DEFAULT_OPERATION_NAME = "unnamed_operation";
  static final String DEFAULT_ENV = "none";

  private static final Logger log = LoggerFactory.getLogger(TraceUtils.class);

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

    name = normalizeSpanName(name);
    if (name.length() == 0) {
      name = DEFAULT_OPERATION_NAME;
    }
    return name;
  }

  public static CharSequence normalizeSpanType(final CharSequence spanType) {
    if (spanType != null && spanType.length() > MAX_TYPE_LEN) {
      log.debug(
          "Fixing malformed trace. Type is too long (reason:type_truncate), truncating span.type to length={}",
          MAX_TYPE_LEN);
      return truncate(spanType, MAX_TYPE_LEN);
    }
    return spanType;
  }

  public static String normalizeEnv(final String env) {
    if (env == null || env.length() == 0) {
      return DEFAULT_ENV;
    }

    String e = truncate(env, MAX_ENV_LEN);
    return normalizeTag(e);
  }

  public static boolean isValidStatusCode(final int httpStatusCode) {
    return (httpStatusCode >= 100 && httpStatusCode < 600);
  }

  // spotless:off

  /**
   * Normalizes a full tag (key:value):
   * - Only letters, digits, ":", ".", "-", "_" and "/" are allowed.
   * - If a non-valid char is found, it's replaced with "_". If it's the last char, it's removed.
   * - It must start with a letter or ":".
   * - It applies lower case.
   *
   * @param tag value
   * @return normalized full tag
   * See https://docs.datadoghq.com/getting_started/tagging/
   */
  // spotless:on
  public static String normalizeTag(final String tag) {
    return doNormalize(tag, true);
  }

  /**
   * Normalizes a tag value according to the datadog tag conventions - Only letters, digits, ":",
   * ".", "-", "_" and "/" are allowed. - If a non-valid char is found, it's replaced with "_". If
   * it's the last char, it's removed. - It applies lower case.
   *
   * @param tagValue the tag value
   * @return normalized tag value See https://docs.datadoghq.com/getting_started/tagging/
   */
  public static String normalizeTagValue(final String tagValue) {
    return doNormalize(tagValue, false);
  }

  private static String doNormalize(String tag, boolean skipNumericalPrefixes) {
    if (tag == null || tag.isEmpty()) {
      return "";
    }

    StringBuilder builder = new StringBuilder(tag.length());
    boolean isJumping = false;
    for (int i = 0; i < tag.length(); ++i) {
      char ch = tag.charAt(i);
      if (ch >= 'a' && ch <= 'z' || ch == ':') {
        isJumping = false;
        builder.append(ch);
        continue;
      }
      if (ch >= 'A' && ch <= 'Z') {
        isJumping = false;
        ch += 'a' - 'A';
        builder.append(ch);
        continue;
      }
      if (Character.isUpperCase(ch)) {
        ch = Character.toLowerCase(ch);
      }
      if (Character.isLetter(ch)) {
        isJumping = false;
        builder.append(ch);
        continue;
      }
      if (builder.length() == 0) {
        if (skipNumericalPrefixes || !Character.isDigit(ch)) {
          // this character can't start the string, trim
          continue;
        }
      }
      if (Character.isDigit(ch) || ch == '.' || ch == '/' || ch == '-') {
        isJumping = false;
        builder.append(ch);
        continue;
      }
      if (!isJumping) {
        builder.append('_');
        isJumping = true;
      }
    }
    // If last added character was due to a bad pattern, just remove the last added underscore
    if (isJumping) {
      builder.setLength(builder.length() - 1);
    }
    return builder.toString();
  }

  // spotless:off

  /**
   * Normalizes the span name:
   * - Only alphanumeric chars, "_" and "." are allowed.
   * - If a non-valid char is found, it's replaced with "_". If it's the last char, it's removed.
   * - Multiple underscores "___" transformed into a single one "_"
   * - Pattern "-." transformed into "."
   *
   * @param name
   * @return normalized span name
   */
  // spotless:on
  private static CharSequence normalizeSpanName(final CharSequence name) {
    if (name.length() == 0) {
      return name;
    }

    final StringBuilder builder = new StringBuilder(name.length());

    int i = 0;
    char previousCh = 0;
    // skip non-alphabetic characters
    for (i = 0; i < name.length() && !isAlpha(name.charAt(i)); i++) {}

    // if there were no alphabetic characters it wasn't valid
    if (i == name.length()) {
      return "";
    }

    for (; i < name.length(); i++) {
      char ch = name.charAt(i);

      if (isAlphaNum(ch)) {
        builder.append(ch);
        previousCh = ch;
      } else if (ch == '.') {
        if (previousCh == '_') {
          // overwrite underscores that happen before periods
          builder.setLength(builder.length() - 1);
        }
        builder.append(ch);
        previousCh = ch;
      } else {
        if (previousCh != '.' && previousCh != '_') {
          builder.append('_');
          previousCh = '_';
        }
      }
    }

    if (previousCh == '_') {
      builder.setLength(builder.length() - 1);
    }
    return builder.toString();
  }

  // fast isAlpha for ascii
  private static boolean isAlpha(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
  }

  // fast isAlphaNumeric for ascii
  private static boolean isAlphaNum(char ch) {
    return isAlpha(ch) || (ch >= '0' && ch <= '9');
  }
}
