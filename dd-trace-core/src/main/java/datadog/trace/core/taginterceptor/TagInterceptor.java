package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.MEASURED;
import static datadog.trace.api.DDTags.ORIGIN_KEY;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_TAGS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
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
import datadog.trace.util.StringIndex;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TagInterceptor {

  private static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");

  /**
   * One intercept behavior, in a uniform shape so every case can live behind a single
   * (devirtualizable) call site. Each {@code switch} arm of the old dispatch is now a method
   * reference stored slot-aligned in {@link #handlers}; dispatch is an array load + this call rather
   * than a 344-byte {@code tableswitch} that overran C2's inline budget.
   */
  @FunctionalInterface
  public interface TagHandler {
    boolean handle(DDSpanContext span, String tag, Object value);
  }

  private final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
  private final boolean splitByServletContext;
  private final String inferredServiceName;
  private final Set<String> splitServiceTags;

  private final boolean shouldSet404ResourceName;
  private final boolean shouldSetUrlResourceAsName;
  private final boolean jeeSplitByDeployment;

  // Per-instance dispatch table: the membership index plus a slot-aligned handler array. Built once
  // at construction, so config-driven split-by-tags fold into the same index as the compile-time
  // fixed tags -- one lookup covers both, and the per-call splitServiceTags.contains is gone.
  private final StringIndex index;
  private final TagHandler[] handlers;

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
    this.splitServiceTags = splitServiceTags;
    this.ruleFlags = ruleFlags;
    splitByServletContext = splitServiceTags.contains(SERVLET_CONTEXT);

    shouldSet404ResourceName =
        ruleFlags.isEnabled(URL_AS_RESOURCE_NAME)
            && ruleFlags.isEnabled(STATUS_404)
            && ruleFlags.isEnabled(STATUS_404_DECORATOR);
    shouldSetUrlResourceAsName = ruleFlags.isEnabled(URL_AS_RESOURCE_NAME);
    this.jeeSplitByDeployment = jeeSplitByDeployment;

    // Assemble the dispatch table. Fixed tags first; split-by-tags only fill slots a fixed tag
    // didn't claim (putIfAbsent), preserving the original "fixed wins" precedence. Handlers keep
    // their own ruleFlags checks for now (behavior-identical to the old switch); hoisting those
    // gates into install/skip decisions here is a clean follow-up.
    Map<String, TagHandler> byName = new HashMap<>();
    byName.put(DDTags.RESOURCE_NAME, this::interceptResourceName);
    byName.put(Tags.DB_STATEMENT, TagInterceptor::interceptDbStatement);
    TagHandler service = this::interceptServiceName;
    byName.put(DDTags.SERVICE_NAME, service);
    byName.put("service", service);
    byName.put(Tags.PEER_SERVICE, this::interceptPeerService);
    byName.put(DDTags.MANUAL_KEEP, TagInterceptor::interceptManualKeep);
    byName.put(DDTags.MANUAL_DROP, this::interceptManualDrop);
    byName.put(Tags.ASM_KEEP, TagInterceptor::interceptAsmKeep);
    byName.put(Tags.AI_GUARD_KEEP, TagInterceptor::interceptAiGuardKeep);
    byName.put(Tags.SAMPLING_PRIORITY, this::interceptSamplingPriority);
    byName.put(Tags.PROPAGATED_TRACE_SOURCE, TagInterceptor::interceptPropagatedTraceSource);
    byName.put(Tags.PROPAGATED_DEBUG, TagInterceptor::interceptPropagatedDebug);
    byName.put(SERVLET_CONTEXT, this::interceptServletContext);
    byName.put(SPAN_TYPE, TagInterceptor::interceptSpanType);
    byName.put(ANALYTICS_SAMPLE_RATE, TagInterceptor::interceptAnalyticsSampleRate);
    byName.put(Tags.ERROR, TagInterceptor::interceptError);
    byName.put(HTTP_STATUS, this::interceptHttpStatusCode);
    TagHandler urlResource = this::interceptUrlResourceAsNameRule;
    byName.put(HTTP_METHOD, urlResource);
    byName.put(HTTP_URL, urlResource);
    byName.put(ORIGIN_KEY, TagInterceptor::interceptOrigin);
    byName.put(MEASURED, TagInterceptor::interceptMeasured);
    byName.put(Tags.SPAN_KIND, TagInterceptor::interceptSpanKind);
    for (String splitTag : splitServiceTags) {
      byName.putIfAbsent(splitTag, TagInterceptor::interceptSplitService);
    }

    String[] names = byName.keySet().toArray(new String[0]);
    this.index = StringIndex.of(names);
    this.handlers = this.index.mapValues(TagHandler.class, byName::get);
  }

  public boolean needsIntercept(TagMap map) {
    for (TagMap.EntryReader entry : map) {
      if (needsIntercept(entry.tag())) return true;
    }
    return false;
  }

  public boolean needsIntercept(Map<String, ?> map) {
    for (String tag : map.keySet()) {
      if (needsIntercept(tag)) return true;
    }
    return false;
  }

  public boolean needsIntercept(String tag) {
    return index.contains(tag);
  }

  /**
   * Resolves {@code tag} to a 1-based dispatch slot, or {@code 0} when not intercepted. The slot is
   * just {@code indexOf + 1}: the index already unifies the compile-time fixed tags with the
   * Config-driven split-by-tags, so a single lookup decides everything. {@link #handleIntercept}
   * turns the slot back into a {@link TagHandler}. The 1-based scheme keeps {@code 0} as the
   * "store, don't intercept" sentinel that {@code DDSpanContext} relies on to skip boxing.
   */
  public int handlerId(String tag) {
    return index.indexOf(tag) + 1;
  }

  /**
   * Convenience: resolve the handler and dispatch. A miss short-circuits (not ours).
   */
  public boolean interceptTag(DDSpanContext span, String tag, Object value) {
    TagHandler handler = index.lookup(handlers, tag);
    return handler != null && handler.handle(span, tag, value);
  }

  public boolean handleIntercept(DDSpanContext span, int handlerId, String tag, Object value) {
    return handlers[handlerId - 1].handle(span, tag, value);
  }

  private boolean interceptUrlResourceAsNameRule(DDSpanContext span, String tag, Object value) {
    if (shouldSetUrlResourceAsName) {
      if (HTTP_METHOD.equals(tag)) {
        final Object url = span.unsafeGetTag(HTTP_URL);
        if (url != null) {
          setResourceFromUrl(span, value.toString(), url);
        }
      } else if (HTTP_URL.equals(tag)) {
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

  private boolean interceptResourceName(DDSpanContext span, String tag, Object value) {
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

  private static boolean interceptDbStatement(DDSpanContext span, String tag, Object value) {
    if (value instanceof CharSequence) {
      CharSequence resourceName = (CharSequence) value;
      if (resourceName.length() > 0) {
        span.setResourceName(resourceName, ResourceNamePriorities.TAG_INTERCEPTOR);
      }
    }
    return true;
  }

  private boolean interceptServiceName(DDSpanContext span, String tag, Object value) {
    return interceptServiceName(SERVICE_NAME, span, value);
  }

  private boolean interceptPeerService(DDSpanContext span, String tag, Object value) {
    // we still need to intercept and add this tag when the user manually set
    span.setTag(DDTags.PEER_SERVICE_SOURCE, Tags.PEER_SERVICE);
    return interceptServiceName(PEER_SERVICE, span, value);
  }

  private static boolean interceptManualKeep(DDSpanContext span, String tag, Object value) {
    if (asBoolean(value)) {
      span.forceKeep();
      return true;
    }
    return false;
  }

  private boolean interceptManualDrop(DDSpanContext span, String tag, Object value) {
    return interceptSamplingPriority(
        FORCE_MANUAL_DROP, USER_DROP, SamplingMechanism.MANUAL, span, value);
  }

  private static boolean interceptAsmKeep(DDSpanContext span, String tag, Object value) {
    if (asBoolean(value)) {
      span.forceKeep(SamplingMechanism.APPSEC);
      return true;
    }
    return false;
  }

  private static boolean interceptAiGuardKeep(DDSpanContext span, String tag, Object value) {
    if (asBoolean(value)) {
      span.forceKeep(SamplingMechanism.AI_GUARD);
      return true;
    }
    return false;
  }

  private static boolean interceptPropagatedTraceSource(
      DDSpanContext span, String tag, Object value) {
    if (value instanceof Integer) {
      span.addPropagatedTraceSource((Integer) value);
      return true;
    }
    return false;
  }

  private static boolean interceptPropagatedDebug(DDSpanContext span, String tag, Object value) {
    span.updateDebugPropagation(String.valueOf(value));
    return true;
  }

  private static boolean interceptSpanKind(DDSpanContext span, String tag, Object value) {
    // Cache the ordinal for fast isOutbound() checks.
    // Return false so the value is still stored in unsafeTags for serialization.
    span.setSpanKindOrdinal(String.valueOf(value));
    return false;
  }

  private static boolean interceptSplitService(DDSpanContext span, String tag, Object value) {
    span.setServiceName(String.valueOf(value), SPLIT_BY_TAGS);
    return true;
  }

  private static boolean interceptError(DDSpanContext span, String tag, Object value) {
    span.setErrorFlag(asBoolean(value), ErrorPriorities.DEFAULT);
    return true;
  }

  private static boolean interceptAnalyticsSampleRate(DDSpanContext span, String tag, Object value) {
    Number analyticsSampleRate = getOrTryParse(value);
    if (null != analyticsSampleRate) {
      span.setMetric(ANALYTICS_SAMPLE_RATE, analyticsSampleRate);
    }
    return true;
  }

  private static boolean interceptSpanType(DDSpanContext span, String tag, Object value) {
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

  private boolean interceptSamplingPriority(DDSpanContext span, String tag, Object value) {
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

  boolean interceptServletContext(DDSpanContext span, String tag, Object value) {
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

  private boolean interceptHttpStatusCode(DDSpanContext span, String tag, Object statusCode) {
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

  private static boolean interceptOrigin(
      final DDSpanContext span, final String tag, final Object origin) {
    if (origin instanceof CharSequence) {
      span.setOrigin((CharSequence) origin);
    } else {
      span.setOrigin(String.valueOf(origin));
    }
    return true;
  }

  private static boolean interceptMeasured(DDSpanContext span, String tag, Object value) {
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
