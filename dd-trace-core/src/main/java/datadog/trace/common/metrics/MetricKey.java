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
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MetricKey metricKey = (MetricKey) o;
    return httpStatusCode == metricKey.httpStatusCode
        && resource.equals(metricKey.resource)
        && service.equals(metricKey.service)
        && operationName.equals(metricKey.operationName)
        && type.equals(metricKey.type);
  }

  @Override
  public int hashCode() {
    return 97 * Objects.hash(resource, service, operationName, type) + httpStatusCode;
  }
}
