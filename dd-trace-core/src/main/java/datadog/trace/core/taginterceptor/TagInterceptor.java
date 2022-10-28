package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.MEASURED;
import static datadog.trace.api.DDTags.ORIGIN_KEY;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_MANUAL_DROP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.PEER_SERVICE;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.RESOURCE_NAME;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.SERVICE_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.DDTags;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import java.util.Set;

public class TagInterceptor {

  private final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
  private final boolean splitByServletContext;
  private final String inferredServiceName;
  private final Set<String> splitServiceTags;

  public TagInterceptor(RuleFlags ruleFlags) {
    this(
        Config.get().isServiceNameSetByUser(),
        CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME),
        Config.get().getSplitByTags(),
        ruleFlags);
  }

  public TagInterceptor(
      boolean isServiceNameSetByUser,
      String inferredServiceName,
      Set<String> splitServiceTags,
      RuleFlags ruleFlags) {
    this.isServiceNameSetByUser = isServiceNameSetByUser;
    this.inferredServiceName = inferredServiceName;
    this.splitServiceTags = splitServiceTags;
    this.ruleFlags = ruleFlags;
    splitByServletContext = splitServiceTags.contains(InstrumentationTags.SERVLET_CONTEXT);
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
      case ORIGIN_KEY:
        return interceptOrigin(span, value);
      case MEASURED:
        return interceptMeasured(span, value);
      default:
        return intercept(span, tag, value);
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
    span.setErrorFlag(asBoolean(value));
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
      span.setServiceName(String.valueOf(value));
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

  private boolean interceptServletContext(DDSpanContext span, Object value) {
    // even though this tag is sometimes used to set the service name
    // (which has the side effect of marking the span as eligible for metrics
    // in the trace agent) we also want to store it in the tags no matter what,
    // so will always return false here.
    if (!splitByServletContext
        && (isServiceNameSetByUser
            || !ruleFlags.isEnabled(RuleFlags.Feature.SERVLET_CONTEXT)
            || !span.getServiceName().isEmpty()
                && !span.getServiceName().equals(inferredServiceName)
                && !span.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME))) {
      return false;
    }
    String contextName = String.valueOf(value).trim();
    if (!contextName.isEmpty()) {
      if (contextName.equals("/")) {
        span.setServiceName(Config.get().getRootContextServiceName());
      } else if (contextName.charAt(0) == '/') {
        if (contextName.length() > 1) {
          span.setServiceName(contextName.substring(1));
        }
      } else {
        span.setServiceName(contextName);
      }
    }
    return false;
  }

  private boolean interceptHttpStatusCode(DDSpanContext span, Object statusCode) {
    if (statusCode instanceof Number) {
      span.setHttpStatusCode(((Number) statusCode).shortValue());
      return true;
    }
    try {
      span.setHttpStatusCode(Short.parseShort(String.valueOf(statusCode)));
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
