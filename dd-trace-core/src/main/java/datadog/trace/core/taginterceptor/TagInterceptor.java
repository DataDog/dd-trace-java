package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
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
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Routes tags this tracer treats as more than storage -- to a span field, a metric, or a sampling
 * directive -- on their way through {@code setTag}.
 *
 * <p>Dispatch is keyed on the tag's registry ID rather than on its name. That buys three things.
 * The pre-screen ({@link #needsIntercept}) becomes a mask test on {@link KnownTagCodec#INTERCEPTED}
 * against an id a stored entry already carries, instead of a switch over strings. The dispatch
 * itself becomes an int switch over dense serials -- a {@code tableswitch}, where a switch over
 * names is a {@code lookupswitch} on string hashes plus an {@code equals()} per hit. And because
 * {@code keyOf} is many->one, every namespace a tag is known by lands on one case: the hand-
 * maintained {@code "service.name"}/{@code "service"} pair collapses into the one {@code service}
 * serial, and an OpenTelemetry name routes without a second label.
 *
 * <p>Which keys carry the INTERCEPTED flag is declared in {@code tag-conventions.java.yaml}, and
 * {@code TagInterceptorRoutingTest} asserts that set is exactly the set this switch handles. That
 * test is what licenses the flag to exist: an earlier version of it was removed precisely because
 * the declaration and the switch could drift apart silently.
 */
public class TagInterceptor {

  private static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");

  private final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
  private final boolean splitByServletContext;
  private final String inferredServiceName;
  private final Set<String> splitServiceTags;
  private final boolean hasSplitServiceTags;

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
    this.splitServiceTags = splitServiceTags;
    this.hasSplitServiceTags = !splitServiceTags.isEmpty();
    this.ruleFlags = ruleFlags;
    splitByServletContext = splitServiceTags.contains(SERVLET_CONTEXT);

    shouldSet404ResourceName =
        ruleFlags.isEnabled(URL_AS_RESOURCE_NAME)
            && ruleFlags.isEnabled(STATUS_404)
            && ruleFlags.isEnabled(STATUS_404_DECORATOR);
    shouldSetUrlResourceAsName = ruleFlags.isEnabled(URL_AS_RESOURCE_NAME);
    this.jeeSplitByDeployment = jeeSplitByDeployment;
  }

  /**
   * True if any entry in {@code map} is routed. Each entry is asked for its own id, so the common
   * answer -- no -- costs a mask test per entry and no name comparison at all.
   */
  public boolean needsIntercept(TagMap map) {
    for (TagMap.EntryReader entry : map) {
      if (needsIntercept(entry.tagId(), entry.tag())) return true;
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
    return needsIntercept(KnownTagCodec.keyOf(tag), tag);
  }

  /**
   * The pre-screen, for a caller that already holds the tag's id. Prefer it: resolving the name
   * once and passing the id to both this and {@link #interceptTag} is the whole point of keying on
   * ids, and it is what keeps a routed tag from being looked up twice.
   *
   * <p>{@code splitServiceTags} is the one case the flag cannot answer. It is user configuration --
   * any tag name at all, including a custom one with no id -- so it stays a set lookup, guarded by
   * the usual case of the feature being off.
   */
  public boolean needsIntercept(long tagId, String tag) {
    return KnownTagCodec.isIntercepted(tagId) || isSplitServiceTag(tag);
  }

  private boolean isSplitServiceTag(String tag) {
    return hasSplitServiceTags && splitServiceTags.contains(tag);
  }

  public boolean interceptTag(DDSpanContext span, String tag, Object value) {
    return interceptTag(span, KnownTagCodec.keyOf(tag), tag, value);
  }

  /**
   * Routes one tag, for a caller that already holds its id. Returns true when the value has been
   * consumed and must NOT also be stored.
   *
   * <p>Whether a routed tag is also stored is decided here, per call, from the value -- it is not a
   * property of the tag: {@code http.url} is routed and always stored, {@code manual.keep} is
   * consumed only when its value coerces to a boolean. That is why the INTERCEPTED flag says only
   * "ask", and this return value stays the authority.
   *
   * <p>{@code tag} is still needed for the {@code splitServiceTags} fallback, which is keyed on the
   * name the user configured rather than on an id.
   */
  public boolean interceptTag(DDSpanContext span, long tagId, String tag, Object value) {
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
        return interceptHttpMethod(span, value);
      case KnownTags.HTTP_URL_SERIAL_NUM:
        return interceptHttpUrl(span, value);
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
        return intercept(span, tag, value);
    }
  }

  private boolean interceptHttpMethod(DDSpanContext span, Object value) {
    if (shouldSetUrlResourceAsName) {
      final Object url = span.unsafeGetTag(HTTP_URL);
      if (url != null) {
        setResourceFromUrl(span, value.toString(), url);
      }
    }
    // always false: the method is routed to the resource name AND stored
    return false;
  }

  private boolean interceptHttpUrl(DDSpanContext span, Object value) {
    if (shouldSetUrlResourceAsName) {
      final Object method = span.unsafeGetTag(HTTP_METHOD);
      setResourceFromUrl(span, method != null ? method.toString() : null, value);
    }
    // always false: the url is routed to the resource name AND stored
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

  private boolean intercept(DDSpanContext span, String tag, Object value) {
    if (splitServiceTags.contains(tag)) {
      span.setServiceName(String.valueOf(value), SPLIT_BY_TAGS);
      return true;
    }
    return false;
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
