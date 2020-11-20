package datadog.trace.core.taginterceptor;

import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.core.ExclusiveSpan;

class ServletContextTagInterceptor extends AbstractTagInterceptor {

  private final boolean isServiceNameSetByUser;
  private final String serviceName;

  public ServletContextTagInterceptor() {
    super(InstrumentationTags.SERVLET_CONTEXT);
    this.isServiceNameSetByUser = Config.get().isServiceNameSetByUser();
    this.serviceName = CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    if (isServiceNameSetByUser
        || (!span.getServiceName().isEmpty()
            && !span.getServiceName().equals(serviceName)
            && !span.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME))) {
      return true;
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
    return true;
  }
}
