package datadog.trace.core;

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

  public String getType() {
    return context.getSpanType();
  }

  public void setType(final String type) {
    context.setSpanType(type);
  }

  /** @return if sampling priority was set by this method invocation */
  public boolean setSamplingPriority(final int newPriority) {
    return context.setSamplingPriority(newPriority);
  }

  public abstract static class Consumer {
    public abstract void accept(ExclusiveSpan span);
  }
}
