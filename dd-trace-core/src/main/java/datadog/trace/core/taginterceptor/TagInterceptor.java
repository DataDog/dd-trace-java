package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.MEASURED;
import static datadog.trace.api.DDTags.ORIGIN_KEY;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
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
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
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

public class TagInterceptor {

  private static final UTF8BytesString NOT_FOUND_RESOURCE_NAME = UTF8BytesString.create("404");

  private final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
  private final boolean splitByServletContext;
  private final String inferredServiceName;
  private final Set<String> splitServiceTags;

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
    this.ruleFlags = ruleFlags;
    splitByServletContext = splitServiceTags.contains(InstrumentationTags.SERVLET_CONTEXT);

    shouldSet404ResourceName =
        ruleFlags.isEnabled(URL_AS_RESOURCE_NAME)
            && ruleFlags.isEnabled(STATUS_404)
            && ruleFlags.isEnabled(STATUS_404_DECORATOR);
    shouldSetUrlResourceAsName = ruleFlags.isEnabled(URL_AS_RESOURCE_NAME);
    this.jeeSplitByDeployment = jeeSplitByDeployment;
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
    switch (tag) {
      case DDTags.RESOURCE_NAME:
      case Tags.DB_STATEMENT:
      case DDTags.SERVICE_NAME:
      case "service":
      case Tags.PEER_SERVICE:
      case DDTags.MANUAL_KEEP:
      case DDTags.MANUAL_DROP:
      case Tags.ASM_KEEP:
      case Tags.SAMPLING_PRIORITY:
      case Tags.PROPAGATED_TRACE_SOURCE:
      case Tags.PROPAGATED_DEBUG:
      case InstrumentationTags.SERVLET_CONTEXT:
      case SPAN_TYPE:
      case ANALYTICS_SAMPLE_RATE:
      case Tags.ERROR:
      case HTTP_STATUS:
      case HTTP_METHOD:
      case HTTP_URL:
      case ORIGIN_KEY:
      case MEASURED:
        return true;

      default:
        return splitServiceTags.contains(tag);
    }
  }

  public boolean interceptTag(DDSpanContext span, String tag, Object value) {
    switch (tag) {
      case DDTags.RESOURCE_NAME:
        return interceptResourceName(span, value);
      case Tags.DB_STATEMENT:
        return interceptDbStatement(span, value);
      case DDTags.SERVICE_NAME:
      case "service":
        return interceptServiceName(SERVICE_NAME, span, value);
      case Tags.PEER_SERVICE:
        // we still need to intercept and add this tag when the user manually set
        span.setTag(DDTags.PEER_SERVICE_SOURCE, Tags.PEER_SERVICE);
        return interceptServiceName(PEER_SERVICE, span, value);
      case DDTags.MANUAL_KEEP:
        if (asBoolean(value)) {
          span.forceKeep();
          return true;
        }
        return false;
      case DDTags.MANUAL_DROP:
        return interceptSamplingPriority(
            FORCE_MANUAL_DROP, USER_DROP, SamplingMechanism.MANUAL, span, value);
      case Tags.ASM_KEEP:
        if (asBoolean(value)) {
          span.forceKeep(SamplingMechanism.APPSEC);
          return true;
        }
        return false;
      case Tags.SAMPLING_PRIORITY:
        return interceptSamplingPriority(span, value);
      case Tags.PROPAGATED_TRACE_SOURCE:
        if (value instanceof Integer) {
          span.addPropagatedTraceSource((Integer) value);
          return true;
        }
        return false;
      case Tags.PROPAGATED_DEBUG:
        span.updateDebugPropagation(String.valueOf(value));
        return true;
      case InstrumentationTags.SERVLET_CONTEXT:
        return interceptServletContext(span, value);
      case SPAN_TYPE:
        return interceptSpanType(span, value);
      case ANALYTICS_SAMPLE_RATE:
        return interceptAnalyticsSampleRate(span, value);
      case Tags.ERROR:
        return interceptError(span, value);
      case HTTP_STATUS:
        // not set internally but may come from manual instrumentation
        return interceptHttpStatusCode(span, value);
      case HTTP_METHOD:
      case HTTP_URL:
        return interceptUrlResourceAsNameRule(span, tag, value);
      case ORIGIN_KEY:
        return interceptOrigin(span, value);
      case MEASURED:
        return interceptMeasured(span, value);
      default:
        return intercept(span, tag, value);
    }
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
      final boolean isClient = Tags.SPAN_KIND_CLIENT.equals(span.unsafeGetTag(Tags.SPAN_KIND));
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
      span.setServiceName(String.valueOf(value));
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

  private boolean interceptServiceName(
      RuleFlags.Feature feature, DDSpanContext span, Object value) {
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
        span.setSamplingPriority(
            samplingPriority.intValue() > 0 ? USER_KEEP : USER_DROP, SamplingMechanism.MANUAL);
      }
      return true;
    }
    return false;
  }

  private boolean interceptServletContext(DDSpanContext span, Object value) {
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
        span.setServiceName(serviceName);
      } else if (contextName.charAt(0) == '/') {
        if (contextName.length() > 1) {
          serviceName = contextName.substring(1);
          span.setServiceName(serviceName);
        }
      } else {
        serviceName = contextName;
        span.setServiceName(serviceName);
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
