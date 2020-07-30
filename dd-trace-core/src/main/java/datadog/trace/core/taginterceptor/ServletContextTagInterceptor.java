package datadog.trace.core.taginterceptor;

import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.core.ExclusiveSpan;

class ServletContextTagInterceptor extends AbstractTagInterceptor {

  public ServletContextTagInterceptor() {
    super(InstrumentationTags.SERVLET_CONTEXT);
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    String contextName = String.valueOf(value).trim();
    if (contextName.equals("/")
        || (!span.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME)
            && !span.getServiceName()
                .equals(CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME))
            && !span.getServiceName().isEmpty())) {
      return true;
    }
    if (contextName.startsWith("/")) {
      if (contextName.length() > 1) {
        contextName = contextName.substring(1);
      }
    }
    if (!contextName.isEmpty()) {
      span.setServiceName(contextName);
    }
    return true;
  }
}
