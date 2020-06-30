package datadog.trace.core.taginterceptor;

import datadog.trace.api.ConfigDefaults;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.core.DDSpanContext;

class ServletContextTagInterceptor extends AbstractTagInterceptor {

  public ServletContextTagInterceptor() {
    super(InstrumentationTags.SERVLET_CONTEXT);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    String contextName = String.valueOf(value).trim();
    if (contextName.equals("/")
        || (!context.getServiceName().equals(ConfigDefaults.DEFAULT_SERVICE_NAME)
            && !context.getServiceName().isEmpty())) {
      return true;
    }
    if (contextName.startsWith("/")) {
      if (contextName.length() > 1) {
        contextName = contextName.substring(1);
      }
    }
    if (!contextName.isEmpty()) {
      context.setServiceName(contextName);
    }
    return true;
  }
}
