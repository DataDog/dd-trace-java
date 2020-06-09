package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.core.DDSpanContext;

public class ServiceNameTagInterceptor extends AbstractTagInterceptor {

  private final boolean setTag;

  public ServiceNameTagInterceptor() {
    this(DDTags.SERVICE_NAME, false);
  }

  public ServiceNameTagInterceptor(final String splitByTag, final boolean setTag) {
    super();
    this.setTag = setTag;
    setMatchingTag(splitByTag);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    context.setServiceName(String.valueOf(value));
    return setTag;
  }
}
