package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.KnownTags.SERVLET_CONTEXT_ID;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_TAGS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_MANUAL_DROP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_SAMPLING_PRIORITY;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.PEER_SERVICE;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.RESOURCE_NAME;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.SERVICE_NAME;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.STATUS_404;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.STATUS_404_DECORATOR;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.URL_AS_RESOURCE_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.DDTags;
import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.KnownTags;
import datadog.trace.api.Pair;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.normalize.HttpResourceNames;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.DDSpanContext;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TagInterceptor {

  private static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");

  private final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
  private final boolean splitByServletContext;
  private final String inferredServiceName;

  /** split-by-tags entries naming known tags, indexed by serial; null when there are none. */
  private final boolean[] splitServiceSerials;

  /** split-by-tags entries naming custom tags; null when there are none. */
  private final Set<String> splitServiceCustomNames;

  private final boolean shouldSet404ResourceName;
  private final boolean shouldSetUrlResourceAsName;
  private final boolean jeeSplitByDeployment;

  public TagInterceptor(RuleFlags ruleFlags) {
    this(
        Config.get().isServiceNameSetByUser(),
        CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME),
        Config.get().getSplitByTags(),
        ruleFlags,
        Config.get().isJeeSplitByDeployment());
  }

  public TagInterceptor(
      boolean isServiceNameSetByUser,
      String inferredServiceName,
      Set<String> splitServiceTags,
      RuleFlags ruleFlags,
      boolean jeeSplitByDeployment) {
    this.isServiceNameSetByUser = isServiceNameSetByUser;
    this.inferredServiceName = inferredServiceName;
    this.splitServiceSerials = knownTagSerials(splitServiceTags);
    this.splitServiceCustomNames = customTagNames(splitServiceTags);
    this.ruleFlags = ruleFlags;
    splitByServletContext = isSplitServiceTag(SERVLET_CONTEXT_ID);

    shouldSet404ResourceName =
        ruleFlags.isEnabled(URL_AS_RESOURCE_NAME)
            && ruleFlags.isEnabled(STATUS_404)
            && ruleFlags.isEnabled(STATUS_404_DECORATOR);
    shouldSetUrlResourceAsName = ruleFlags.isEnabled(URL_AS_RESOURCE_NAME);
    this.jeeSplitByDeployment = jeeSplitByDeployment;
  }

  /**
   * Marks, by serial, each known tag that {@code names} names under any of its names, so a tag set
   * by id or by its OpenTelemetry name matches too. A name declared per direction marks each
   * direction's tag. Returns null when no name is a known tag's.
   */
  private static boolean[] knownTagSerials(Set<String> names) {
    if (names.isEmpty()) {
      return null;
    }
    Set<String> canonicalNames = new HashSet<>();
    for (String name : names) {
      canonicalNames.add(KnownTagCodec.canonicalTagName(name));
    }
    boolean[] serials = null;
    for (int serial = 1; ; ++serial) {
      String name = KnownTagCodec.nameOf(KnownTagCodec.makeTagId(serial));
      if (name == null) {
        return serials;
      }
      if (canonicalNames.contains(name)) {
        if (serials == null || serial >= serials.length) {
          serials = serials == null ? new boolean[serial + 1] : Arrays.copyOf(serials, serial + 1);
        }
        serials[serial] = true;
      }
    }
  }

  /** The names in {@code names} that do not resolve to a known tag, or null when there are none. */
  private static Set<String> customTagNames(Set<String> names) {
    Set<String> custom = null;
    for (String name : names) {
      if (KnownTagCodec.keyOf(name) == 0) {
        if (custom == null) {
          custom = new HashSet<>();
        }
        custom.add(name);
      }
    }
    return custom;
  }

  private boolean isSplitServiceTag(long tagId) {
    boolean[] serials = splitServiceSerials;
    int serial = KnownTagCodec.serialNum(tagId);
    return serials != null && serial < serials.length && serials[serial];
  }

  private boolean isSplitServiceTag(String customTag) {
    Set<String> names = splitServiceCustomNames;
    return names != null && names.contains(customTag);
  }

  public boolean needsIntercept(TagMap map) {
    for (TagMap.EntryReader entry : map) {
      long tagId = entry.tagId();
      if (tagId != 0 ? needsIntercept(tagId) : isSplitServiceTag(entry.tag())) return true;
    }
    return false;
  }

  public boolean needsIntercept(Map<String, ?> map) {
    for (String tag : map.keySet()) {
      if (needsIntercept(tag)) return true;
    }
    return false;
  }

  /**
   * Whether {@link #interceptTag(DDSpanContext, long, Object)} may route the tag. Called with a
   * constant id, the {@link KnownTagCodec#INTERCEPTED} test folds away; only a configured
   * split-by-tags is left to check at run time.
   */
  public boolean needsIntercept(long tagId) {
    return KnownTagCodec.isIntercepted(tagId) || isSplitServiceTag(tagId);
  }

  public boolean needsIntercept(String tag) {
    long tagId = KnownTagCodec.keyOf(tag);
    return tagId != 0 ? needsIntercept(tagId) : isSplitServiceTag(tag);
  }

  public boolean interceptTag(DDSpanContext span, String tag, Object value) {
    long tagId = KnownTagCodec.keyOf(tag);
    return tagId != 0 ? interceptTag(span, tagId, value) : interceptCustomTag(span, tag, value);
  }

  /**
   * Routes a known tag to a span field or a sampling directive. Returns true when the tag was
   * consumed, false when it should still be stored. Every tag with a case here carries the {@link
   * KnownTagCodec#INTERCEPTED} bit, declared in {@code tag-conventions-java.yaml}.
   */
  public boolean interceptTag(DDSpanContext span, long tagId, Object value) {
    switch (KnownTagCodec.serialNum(tagId)) {
      case KnownTags.RESOURCE_NAME_SERIAL_NUM:
        return interceptResourceName(span, value);
      case KnownTags.DB_STATEMENT_SERIAL_NUM:
        return interceptDbStatement(span, value);
      case KnownTags.SERVICE_SERIAL_NUM:
        return interceptServiceName(SERVICE_NAME, span, value);
      case KnownTags.PEER_SERVICE_SERIAL_NUM:
        // we still need to intercept and add this tag when the user manually set
        span.setTag(DDTags.PEER_SERVICE_SOURCE, Tags.PEER_SERVICE);
        return interceptServiceName(PEER_SERVICE, span, value);
      case KnownTags.MANUAL_KEEP_SERIAL_NUM:
        if (asBoolean(value)) {
          span.forceKeep();
          return true;
        }
        return false;
      case KnownTags.MANUAL_DROP_SERIAL_NUM:
        return interceptSamplingPriority(
            FORCE_MANUAL_DROP, USER_DROP, SamplingMechanism.MANUAL, span, value);
      case KnownTags.ASM_KEEP_SERIAL_NUM:
        if (asBoolean(value)) {
          span.forceKeep(SamplingMechanism.APPSEC);
          return true;
        }
        return false;
      case KnownTags.AI_GUARD_KEEP_SERIAL_NUM:
        if (asBoolean(value)) {
          span.forceKeep(SamplingMechanism.AI_GUARD);
          return true;
        }
        return false;
      case KnownTags.SAMPLING_PRIORITY_SERIAL_NUM:
        return interceptSamplingPriority(span, value);
      case KnownTags.DD_P_TS_SERIAL_NUM:
        if (value instanceof Integer) {
          span.addPropagatedTraceSource((Integer) value);
          return true;
        }
        return false;
      case KnownTags.DD_P_DEBUG_SERIAL_NUM:
        span.updateDebugPropagation(String.valueOf(value));
        return true;
      case KnownTags.SERVLET_CONTEXT_SERIAL_NUM:
        return interceptServletContext(span, value);
      case KnownTags.SPAN_TYPE_SERIAL_NUM:
        return interceptSpanType(span, value);
      case KnownTags.DD1_SR_EAUSR_SERIAL_NUM:
        return interceptAnalyticsSampleRate(span, value);
      case KnownTags.ERROR_SERIAL_NUM:
        return interceptError(span, value);
      case KnownTags.HTTP_STATUS_CODE_SERIAL_NUM:
        // not set internally but may come from manual instrumentation
        return interceptHttpStatusCode(span, value);
      case KnownTags.HTTP_METHOD_SERIAL_NUM:
      case KnownTags.HTTP_URL_SERIAL_NUM:
        return interceptUrlResourceAsNameRule(span, tagId, value);
      case KnownTags.DD_ORIGIN_SERIAL_NUM:
        return interceptOrigin(span, value);
      case KnownTags.DD_MEASURED_SERIAL_NUM:
        return interceptMeasured(span, value);
      case KnownTags.SPAN_KIND_SERIAL_NUM:
        // Cache the ordinal for fast isOutbound() checks.
        // Return false so the value is still stored in unsafeTags for serialization.
        span.setSpanKindOrdinal(String.valueOf(value));
        return false;
      default:
        return isSplitServiceTag(tagId) && splitService(span, value);
    }
  }

  private boolean interceptCustomTag(DDSpanContext span, String tag, Object value) {
    return isSplitServiceTag(tag) && splitService(span, value);
  }

  private static boolean splitService(DDSpanContext span, Object value) {
    span.setServiceName(String.valueOf(value), SPLIT_BY_TAGS);
    return true;
  }

  private boolean interceptUrlResourceAsNameRule(DDSpanContext span, long tagId, Object value) {
    if (shouldSetUrlResourceAsName) {
      // Values are stored under their Datadog name, whichever spelling set them.
      if (tagId == KnownTags.HTTP_METHOD_ID) {
        final Object url = span.unsafeGetTag(HTTP_URL);
        if (url != null) {
          setResourceFromUrl(span, value.toString(), url);
        }
      } else {
        final Object method = span.unsafeGetTag(HTTP_METHOD);
        setResourceFromUrl(span, method != null ? method.toString() : null, value);
      }
    }
    return false;
  }

  private static void setResourceFromUrl(
      @Nonnull final DDSpanContext span, @Nullable final String method, @Nonnull final Object url) {
    final String path;
    if (url instanceof URIUtils.LazyUrl) {
      path = ((URIUtils.LazyUrl) url).path();
    } else {
      URI uri = URIUtils.safeParse(url.toString());
      path = uri == null ? null : uri.getPath();
    }
    if (path != null) {
      final boolean isClient = Tags.SPAN_KIND_CLIENT.equals(span.getSpanKindString());
      Pair<CharSequence, Byte> normalized =
          isClient
              ? HttpResourceNames.computeForClient(method, path, false)
              : HttpResourceNames.computeForServer(method, path, false);
      if (normalized.hasLeft()) {
        span.setResourceName(normalized.getLeft(), normalized.getRight());
      }
    } else {
      span.setResourceName(
          HttpResourceNames.DEFAULT_RESOURCE_NAME, ResourceNamePriorities.HTTP_PATH_NORMALIZER);
    }
  }

  private boolean interceptResourceName(DDSpanContext span, Object value) {
    if (ruleFlags.isEnabled(RESOURCE_NAME)) {
      if (null == value) {
        return false;
      }
      if (value instanceof CharSequence) {
        span.setResourceName((CharSequence) value, ResourceNamePriorities.TAG_INTERCEPTOR);
      } else {
        span.setResourceName(String.valueOf(value), ResourceNamePriorities.TAG_INTERCEPTOR);
      }
      return true;
    }
    return false;
  }

  private boolean interceptDbStatement(DDSpanContext span, Object value) {
    if (value instanceof CharSequence) {
      CharSequence resourceName = (CharSequence) value;
      if (resourceName.length() > 0) {
        span.setResourceName(resourceName, ResourceNamePriorities.TAG_INTERCEPTOR);
      }
    }
    return true;
  }

  private boolean interceptError(DDSpanContext span, Object value) {
    span.setErrorFlag(asBoolean(value), ErrorPriorities.DEFAULT);
    return true;
  }

  private boolean interceptAnalyticsSampleRate(DDSpanContext span, Object value) {
    Number analyticsSampleRate = getOrTryParse(value);
    if (null != analyticsSampleRate) {
      span.setMetric(ANALYTICS_SAMPLE_RATE, analyticsSampleRate);
    }
    return true;
  }

  private boolean interceptSpanType(DDSpanContext span, Object value) {
    if (value instanceof CharSequence) {
      span.setSpanType((CharSequence) value);
    } else {
      span.setSpanType(String.valueOf(value));
    }
    return true;
  }

  boolean interceptServiceName(RuleFlags.Feature feature, DDSpanContext span, Object value) {
    if (ruleFlags.isEnabled(feature)) {
      String serviceName = String.valueOf(value);
      span.setServiceName(serviceName);
      ServiceNameCollector.get().addService(serviceName);
      return true;
    }
    return false;
  }

  private boolean interceptSamplingPriority(
      RuleFlags.Feature feature,
      int samplingPriority,
      int samplingMechanism,
      DDSpanContext span,
      Object value) {
    if (ruleFlags.isEnabled(feature)) {
      if (asBoolean(value)) {
        span.setSamplingPriority(samplingPriority, samplingMechanism);
      }
      return true;
    }
    return false;
  }

  private boolean interceptSamplingPriority(DDSpanContext span, Object value) {
    if (ruleFlags.isEnabled(FORCE_SAMPLING_PRIORITY)) {
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
    return false;
  }

  boolean interceptServletContext(DDSpanContext span, Object value) {
    // even though this tag is sometimes used to set the service name
    // (which has the side effect of marking the span as eligible for metrics
    // in the trace agent) we also want to store it in the tags no matter what,
    // so will always return false here.
    if (!splitByServletContext
        && (isServiceNameSetByUser
            || jeeSplitByDeployment
            || !ruleFlags.isEnabled(RuleFlags.Feature.SERVLET_CONTEXT)
            || !span.getServiceName().isEmpty()
                && !span.getServiceName().equals(inferredServiceName)
                && !span.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME))) {
      return false;
    }
    String contextName = String.valueOf(value).trim();
    if (!contextName.isEmpty()) {
      String serviceName = null;
      if (contextName.equals("/")) {
        serviceName = Config.get().getRootContextServiceName();
        span.setServiceName(serviceName, SPLIT_BY_SERVLET_CONTEXT);
      } else if (contextName.charAt(0) == '/') {
        if (contextName.length() > 1) {
          serviceName = contextName.substring(1);
          span.setServiceName(serviceName, SPLIT_BY_SERVLET_CONTEXT);
        }
      } else {
        serviceName = contextName;
        span.setServiceName(serviceName, SPLIT_BY_SERVLET_CONTEXT);
      }
      ServiceNameCollector.get().addService(serviceName);
    }
    return false;
  }

  private boolean interceptHttpStatusCode(DDSpanContext span, Object statusCode) {
    if (statusCode instanceof Number) {
      span.setHttpStatusCode(((Number) statusCode).shortValue());
      if (shouldSet404ResourceName && span.getHttpStatusCode() == 404) {
        span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
      }
      return true;
    }
    try {
      span.setHttpStatusCode(Short.parseShort(String.valueOf(statusCode)));
      if (shouldSet404ResourceName && span.getHttpStatusCode() == 404) {
        span.setResourceName(NOT_FOUND_RESOURCE_NAME, ResourceNamePriorities.HTTP_404);
      }
      return true;
    } catch (Throwable ignore) {
    }
    return false;
  }

  private boolean interceptOrigin(final DDSpanContext span, final Object origin) {
    if (origin instanceof CharSequence) {
      span.setOrigin((CharSequence) origin);
    } else {
      span.setOrigin(String.valueOf(origin));
    }
    return true;
  }

  private static boolean interceptMeasured(DDSpanContext span, Object value) {
    if ((value instanceof Number && ((Number) value).intValue() > 0) || asBoolean(value)) {
      span.setMeasured(true);
      return true;
    }
    return false;
  }

  private static boolean asBoolean(Object value) {
    return Boolean.TRUE.equals(value)
        || "1".equals(value)
        || (!Boolean.FALSE.equals(value) && Boolean.parseBoolean(String.valueOf(value)));
  }

  private static Number getOrTryParse(Object value) {
    if (value instanceof Number) {
      return (Number) value;
    } else if (value instanceof String) {
      try {
        return Double.parseDouble((String) value);
      } catch (NumberFormatException ignore) {

      }
    }
    return null;
  }
}
