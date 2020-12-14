package datadog.trace.core.taginterceptor;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.DDTags.SPAN_TYPE;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_MANUAL_DROP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.FORCE_MANUAL_KEEP;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.PEER_SERVICE;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.RESOURCE_NAME;
import static datadog.trace.core.taginterceptor.RuleFlags.Feature.SERVICE_NAME;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.DDTags;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import java.util.Set;

public class TagInterceptor {

  private final RuleFlags ruleFlags;
  private final boolean isServiceNameSetByUser;
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
        return interceptSamplingPriority(FORCE_MANUAL_KEEP, USER_KEEP, span, value);
      case DDTags.MANUAL_DROP:
        return interceptSamplingPriority(FORCE_MANUAL_DROP, USER_DROP, span, value);
      case InstrumentationTags.SERVLET_CONTEXT:
        return interceptServletContext(span, value);
      case SPAN_TYPE:
        return interceptSpanType(span, value);
      case ANALYTICS_SAMPLE_RATE:
        return interceptAnalyticsSampleRate(span, value);
      case Tags.ERROR:
        return interceptError(span, value);
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
        span.setResourceName((CharSequence) value);
      } else {
        span.setResourceName(String.valueOf(value));
      }
      return true;
    }
    return false;
  }

  private boolean interceptDbStatement(DDSpanContext span, Object value) {
    if (value instanceof CharSequence) {
      CharSequence resourceName = (CharSequence) value;
      if (resourceName.length() > 0) {
        span.setResourceName(resourceName);
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
      RuleFlags.Feature feature, int priority, DDSpanContext span, Object value) {
    if (ruleFlags.isEnabled(feature)) {
      if (asBoolean(value)) {
        span.setSamplingPriority(priority);
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
    if (isServiceNameSetByUser
        || !ruleFlags.isEnabled(RuleFlags.Feature.SERVLET_CONTEXT)
        || (!span.getServiceName().isEmpty()
            && !span.getServiceName().equals(inferredServiceName)
            && !span.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME))) {
      return false;
    }
    String contextName = String.valueOf(value).trim();
    if (!contextName.isEmpty()) {
      if (contextName.charAt(0) == '/') {
        if (contextName.length() > 1) {
          span.setServiceName(contextName.substring(1));
        }
      } else {
        span.setServiceName(contextName);
      }
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
