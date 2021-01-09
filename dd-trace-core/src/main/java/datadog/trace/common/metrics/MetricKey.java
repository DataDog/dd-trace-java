package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Objects;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString operationName;
  private final UTF8BytesString type;
  private final int httpStatusCode;
  private final int hash;

  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence type,
      int httpStatusCode) {
    this.resource = null == resource ? EMPTY : UTF8BytesString.create(resource);
    this.service = null == service ? EMPTY : UTF8BytesString.create(service);
    this.operationName = null == operationName ? EMPTY : UTF8BytesString.create(operationName);
    this.type = null == type ? EMPTY : UTF8BytesString.create(type);
    this.httpStatusCode = httpStatusCode;
    // unrolled polynomial hashcode which avoids allocating varargs
    // the constants are 31^4, 31^3, 31^2, 31^1, 31^0
    this.hash = 923521 * this.resource.hashCode()
      + 29791 * this.service.hashCode()
      + 961 * this.operationName.hashCode()
      + 31 * this.type.hashCode()
      + httpStatusCode;
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

  @Override
  public boolean equals(Object o) {
    try {
      MetricKey metricKey = (MetricKey) o;
      return hash == metricKey.hash
        && httpStatusCode == metricKey.httpStatusCode
        && resource.equals(metricKey.resource)
        && service.equals(metricKey.service)
        && operationName.equals(metricKey.operationName)
        && type.equals(metricKey.type);
    } catch (ClassCastException unlikely) { }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
