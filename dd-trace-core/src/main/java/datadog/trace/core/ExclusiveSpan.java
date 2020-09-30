package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;

/** Internal API for a span where the holder has exclusive access to the resources exposed. */
public final class ExclusiveSpan {
  private final DDSpanContext context;

  /**
   * Should only be created from inside the {@link DDSpanContext}, and accessed via the {@link
   * DDSpanContext#processExclusiveSpan(Consumer)} method.
   *
   * @param context the context that this exclusive span wraps
   */
  ExclusiveSpan(DDSpanContext context) {
    this.context = context;
  }

  public Object getTag(final String tag) {
    return context.unsafeGetTag(tag);
  }

  public void setTag(final String tag, final Object value) {
    context.unsafeSetTag(tag, value);
  }

  public Object getAndRemoveTag(final String tag) {
    return context.unsafeGetAndRemoveTag(tag);
  }

  public void setMetric(final String key, final Number value) {
    context.setMetric(key, value);
  }

  public boolean isResourceNameSet() {
    return context.isResourceNameSet();
  }

  public void setResourceName(final CharSequence resourceName) {
    context.setResourceName(resourceName);
  }

  public String getServiceName() {
    return context.getServiceName();
  }

  public void setServiceName(final String serviceName) {
    context.setServiceName(serviceName);
  }

  public boolean isError() {
    return context.getErrorFlag();
  }

  public void setError(final boolean error) {
    context.setErrorFlag(error);
  }

  public CharSequence getType() {
    return context.getSpanType();
  }

  public void setType(final CharSequence type) {
    context.setSpanType(type);
  }

  public int getHttpStatus() {
    Object status = getTag(HTTP_STATUS);
    if (status instanceof Number) {
      return ((Number) status).intValue();
    }
    if (null != status) {
      try {
        return Integer.parseInt(String.valueOf(status));
      } catch (NumberFormatException ignored) {
      }
    }
    return 0;
  }

  /** @return if sampling priority was set by this method invocation */
  public boolean setSamplingPriority(final int newPriority) {
    return context.setSamplingPriority(newPriority);
  }

  public abstract static class Consumer {
    public abstract void accept(ExclusiveSpan span);
  }
}
