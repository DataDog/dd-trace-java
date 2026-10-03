package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDTags;

/**
 * Precedence among the decorator-owned identity tags ({@code component}, {@code language}, {@code
 * span.kind}) when more than one is configured as a {@code trace.split-by-tags} candidate and they
 * land on a span together, e.g. via a single {@code SpanPrototype} application. Mirrors the
 * precedence the old, sequential {@code setTag} calls in {@code ServerDecorator}/{@code
 * BaseDecorator} used to give for free (span kind, then language, then component last).
 *
 * <p>Any other split-by-tags candidate (a user-configured custom tag, or one set directly by
 * instrumentation code rather than via a decorator's prototype) is not one of these known identity
 * tags, so it always outranks them -- matching the old behavior where such a tag's {@code setTag}
 * call, being outside the decorator's fixed sequence, was never contended with span
 * kind/language/component.
 */
public final class SplitByTagsPriorities {
  public static final byte UNSET = 0;
  public static final byte SPAN_KIND = 1;
  public static final byte LANGUAGE = 2;
  public static final byte COMPONENT = 3;
  public static final byte OTHER = Byte.MAX_VALUE;

  public static byte of(String tag) {
    if (Tags.SPAN_KIND.equals(tag)) {
      return SPAN_KIND;
    }
    if (DDTags.LANGUAGE_TAG_KEY.equals(tag)) {
      return LANGUAGE;
    }
    if (Tags.COMPONENT.equals(tag)) {
      return COMPONENT;
    }
    return OTHER;
  }

  private SplitByTagsPriorities() {}
}
