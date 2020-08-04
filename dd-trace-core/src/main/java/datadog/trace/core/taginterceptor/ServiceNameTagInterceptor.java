package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDTags;
import datadog.trace.core.ExclusiveSpan;

class ServiceNameTagInterceptor extends AbstractTagInterceptor {

  private final boolean setTag;

  public ServiceNameTagInterceptor() {
    this(DDTags.SERVICE_NAME, false);
  }

  public ServiceNameTagInterceptor(final String splitByTag, final boolean setTag) {
    super(splitByTag);
    this.setTag = setTag;
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    span.setServiceName(String.valueOf(value));
    return setTag;
  }
}
