package datadog.trace.core.taginterceptor;

import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_MANUAL_DROP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_SAMPLING_PRIORITY;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.SERVICE_NAME;
import static datadog.trace.core.taginterceptor.TagInterceptor.asBoolean;
import static datadog.trace.core.taginterceptor.TagInterceptor.getOrTryParse;
import static datadog.trace.core.taginterceptor.TagInterceptor.setResourceFromUrl;

import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import java.util.Arrays;

/**
 * What {@link TagInterceptor} does with one known tag, chosen by the tag's serial.
 *
 * <p>A constant id inlines all the way to its handler in two stages: {@link #forSerial} is small
 * enough for C2 to inline, and with a constant serial it folds to one handler, a constant of exact
 * type, whose {@link #intercept} call binds statically and inlines. With an id that varies, the
 * call site sees many handlers and stays a virtual call, so no handler body lands in the compiled
 * code of {@code DDSpanContext.setTag}. {@code TagInterceptorInliningTest} pins the bytecode sizes
 * this needs.
 *
 * <p>Each body is written out here rather than delegating, to keep the inlining depth from an
 * instrumentation call down.
 */
enum InterceptHandler {
  /** A tag with no case of its own: routed only when it is a split-by-tags entry. */
  SPLIT_BY_TAGS(0) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      return interceptor.isSplitServiceTag(tagId) && TagInterceptor.splitService(span, value);
    }
  },
  RESOURCE_NAME(KnownTags.RESOURCE_NAME_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (!interceptor.ruleFlags.isEnabled(RuleFlags.Feature.RESOURCE_NAME) || null == value) {
        return false;
      }
      if (value instanceof CharSequence) {
        span.setResourceName((CharSequence) value, ResourceNamePriorities.TAG_INTERCEPTOR);
      } else {
        span.setResourceName(String.valueOf(value), ResourceNamePriorities.TAG_INTERCEPTOR);
      }
      return true;
    }
  },
  DB_STATEMENT(KnownTags.DB_STATEMENT_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (value instanceof CharSequence) {
        CharSequence resourceName = (CharSequence) value;
        if (resourceName.length() > 0) {
          span.setResourceName(resourceName, ResourceNamePriorities.TAG_INTERCEPTOR);
        }
      }
      return true;
    }
  },
  SERVICE(KnownTags.SERVICE_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      return interceptor.interceptServiceName(SERVICE_NAME, span, value);
    }
  },
  PEER_SERVICE(KnownTags.PEER_SERVICE_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      // we still need to intercept and add this tag when the user manually set
      span.setTag(DDTags.PEER_SERVICE_SOURCE, Tags.PEER_SERVICE);
      return interceptor.interceptServiceName(RuleFlags.Feature.PEER_SERVICE, span, value);
    }
  },
  MANUAL_KEEP(KnownTags.MANUAL_KEEP_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (asBoolean(value)) {
        span.forceKeep();
        return true;
      }
      return false;
    }
  },
  MANUAL_DROP(KnownTags.MANUAL_DROP_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (!interceptor.ruleFlags.isEnabled(FORCE_MANUAL_DROP)) {
        return false;
      }
      if (asBoolean(value)) {
        span.setSamplingPriority(USER_DROP, SamplingMechanism.MANUAL);
      }
      return true;
    }
  },
  ASM_KEEP(KnownTags.ASM_KEEP_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (asBoolean(value)) {
        span.forceKeep(SamplingMechanism.APPSEC);
        return true;
      }
      return false;
    }
  },
  AI_GUARD_KEEP(KnownTags.AI_GUARD_KEEP_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (asBoolean(value)) {
        span.forceKeep(SamplingMechanism.AI_GUARD);
        return true;
      }
      return false;
    }
  },
  SAMPLING_PRIORITY(KnownTags.SAMPLING_PRIORITY_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (!interceptor.ruleFlags.isEnabled(FORCE_SAMPLING_PRIORITY)) {
        return false;
      }
      Number samplingPriority = getOrTryParse(value);
      if (null != samplingPriority) {
        if (samplingPriority.intValue() > 0) {
          span.forceKeep(SamplingMechanism.MANUAL);
        } else {
          span.setSamplingPriority(USER_DROP, SamplingMechanism.MANUAL);
        }
      }
      return true;
    }
  },
  DD_P_TS(KnownTags.DD_P_TS_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (value instanceof Integer) {
        span.addPropagatedTraceSource((Integer) value);
        return true;
      }
      return false;
    }
  },
  DD_P_DEBUG(KnownTags.DD_P_DEBUG_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      span.updateDebugPropagation(String.valueOf(value));
      return true;
    }
  },
  SERVLET_CONTEXT(KnownTags.SERVLET_CONTEXT_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      return interceptor.interceptServletContext(span, value);
    }
  },
  SPAN_TYPE(KnownTags.SPAN_TYPE_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (value instanceof CharSequence) {
        span.setSpanType((CharSequence) value);
      } else {
        span.setSpanType(String.valueOf(value));
      }
      return true;
    }
  },
  DD1_SR_EAUSR(KnownTags.DD1_SR_EAUSR_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      Number analyticsSampleRate = getOrTryParse(value);
      if (null != analyticsSampleRate) {
        span.setMetric(DDTags.ANALYTICS_SAMPLE_RATE, analyticsSampleRate);
      }
      return true;
    }
  },
  ERROR(KnownTags.ERROR_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      span.setErrorFlag(asBoolean(value), ErrorPriorities.DEFAULT);
      return true;
    }
  },
  /** Not set internally but may come from manual instrumentation. */
  HTTP_STATUS_CODE(KnownTags.HTTP_STATUS_CODE_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (value instanceof Number) {
        span.setHttpStatusCode(((Number) value).shortValue());
        if (interceptor.shouldSet404ResourceName && span.getHttpStatusCode() == 404) {
          span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
        }
        return true;
      }
      try {
        span.setHttpStatusCode(Short.parseShort(String.valueOf(value)));
        if (interceptor.shouldSet404ResourceName && span.getHttpStatusCode() == 404) {
          span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
        }
        return true;
      } catch (Throwable ignore) {
      }
      return false;
    }
  },
  HTTP_METHOD(KnownTags.HTTP_METHOD_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (interceptor.shouldSetUrlResourceAsName) {
        // Read by Datadog name only: entries are stored under it whichever spelling set them.
        Object url = span.unsafeGetTag(Tags.HTTP_URL);
        if (url != null) {
          setResourceFromUrl(span, value.toString(), url);
        }
      }
      return false;
    }
  },
  HTTP_URL(KnownTags.HTTP_URL_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (interceptor.shouldSetUrlResourceAsName) {
        // Read by Datadog name only: entries are stored under it whichever spelling set them.
        Object method = span.unsafeGetTag(Tags.HTTP_METHOD);
        setResourceFromUrl(span, method != null ? method.toString() : null, value);
      }
      return false;
    }
  },
  DD_ORIGIN(KnownTags.DD_ORIGIN_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if (value instanceof CharSequence) {
        span.setOrigin((CharSequence) value);
      } else {
        span.setOrigin(String.valueOf(value));
      }
      return true;
    }
  },
  DD_MEASURED(KnownTags.DD_MEASURED_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      if ((value instanceof Number && ((Number) value).intValue() > 0) || asBoolean(value)) {
        span.setMeasured(true);
        return true;
      }
      return false;
    }
  },
  SPAN_KIND(KnownTags.SPAN_KIND_SERIAL_NUM) {
    @Override
    boolean intercept(TagInterceptor interceptor, DDSpanContext span, long tagId, Object value) {
      // Cache the ordinal for fast isOutbound() checks.
      // Return false so the value is still stored in unsafeTags for serialization.
      span.setSpanKindOrdinal(String.valueOf(value));
      return false;
    }
  };

  static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");

  /** Each handler by the serial of its tag; {@link #SPLIT_BY_TAGS} for every other serial. */
  private static final InterceptHandler[] BY_SERIAL =
      new InterceptHandler[KnownTagCodec.serialLimit()];

  static {
    Arrays.fill(BY_SERIAL, SPLIT_BY_TAGS);
    for (InterceptHandler handler : values()) {
      if (handler != SPLIT_BY_TAGS) {
        BY_SERIAL[handler.serial] = handler;
      }
    }
  }

  /** The serial of the tag this handles; 0 for {@link #SPLIT_BY_TAGS}. */
  final int serial;

  InterceptHandler(int serial) {
    this.serial = serial;
  }

  /**
   * Routes the tag to a span field or a sampling directive. Returns true when the tag was consumed,
   * false when it should still be stored.
   */
  abstract boolean intercept(
      TagInterceptor interceptor, DDSpanContext span, long tagId, Object value);

  /**
   * The handler for a known tag's serial, from the table. For ids that are resolved at run time --
   * by name, or from an entry -- where nothing folds, so a switch would only add code. Every tag
   * with a handler carries the {@code KnownTagCodec.INTERCEPTED} bit, declared in {@code
   * tag-conventions-java.yaml}.
   */
  static InterceptHandler bySerial(int serial) {
    return BY_SERIAL[serial];
  }

  /**
   * The handler for a known tag's serial, for an id that is usually a constant at the call site.
   * Agrees with {@link #bySerial} for every serial.
   *
   * <p>The hot tags -- ones instrumentation sets by constant id -- each get an arm that returns a
   * constant, so a constant serial folds to its handler and the handler inlines. Every other tag
   * comes from the table, which C2 never folds, so a cold tag costs a virtual call. Keeping the
   * arms few matters where the id varies: there this method compiles into {@code
   * DDSpanContext.setTag}'s own code, every arm included, and that code must stay under C2's {@code
   * InlineSmallCode} for constant-id callers to inline setTag at all.
   */
  static InterceptHandler forSerial(int serial) {
    switch (serial) {
      case KnownTags.SPAN_KIND_SERIAL_NUM:
        return SPAN_KIND;
      case KnownTags.HTTP_STATUS_CODE_SERIAL_NUM:
        return HTTP_STATUS_CODE;
      case KnownTags.ERROR_SERIAL_NUM:
        return ERROR;
      default:
        return bySerial(serial);
    }
  }
}
