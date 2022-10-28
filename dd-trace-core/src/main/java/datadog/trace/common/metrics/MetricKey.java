package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString operationName;
  private final UTF8BytesString type;
  private final int httpStatusCode;
  private final boolean synthetics;
  private final int hash;

  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence type,
      int httpStatusCode,
      boolean synthetics) {
    this.resource = null == resource ? EMPTY : UTF8BytesString.create(resource);
    this.service = null == service ? EMPTY : UTF8BytesString.create(service);
    this.operationName = null == operationName ? EMPTY : UTF8BytesString.create(operationName);
    this.type = null == type ? EMPTY : UTF8BytesString.create(type);
    this.httpStatusCode = httpStatusCode;
    this.synthetics = synthetics;
    // unrolled polynomial hashcode which avoids allocating varargs
    // the constants are 31^5, 31^4, 31^3, 31^2, 31^1, 31^0
    this.hash =
        28629151 * this.resource.hashCode()
            + 923521 * this.service.hashCode()
            + 29791 * this.operationName.hashCode()
            + 961 * this.type.hashCode()
            + 31 * httpStatusCode
            + (this.synthetics ? 1 : 0);
  }

  public UTF8BytesString getResource() {
    return resource;
  }

  public UTF8BytesString getService() {
    return service;
  }

  public UTF8BytesString getOperationName() {
    return operationName;
  }

  public UTF8BytesString getType() {
    return type;
  }

  public int getHttpStatusCode() {
    return httpStatusCode;
  }

  public boolean isSynthetics() {
    return synthetics;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if ((o instanceof MetricKey)) {
      MetricKey metricKey = (MetricKey) o;
      return hash == metricKey.hash
          && synthetics == metricKey.synthetics
          && httpStatusCode == metricKey.httpStatusCode
          && resource.equals(metricKey.resource)
          && service.equals(metricKey.service)
          && operationName.equals(metricKey.operationName)
          && type.equals(metricKey.type);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
