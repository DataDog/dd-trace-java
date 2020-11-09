package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Objects;

public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString operationName;
  private final int httpStatusCode;

  public MetricKey(
      CharSequence resource, CharSequence service, CharSequence operationName, int httpStatusCode) {
    this.resource = UTF8BytesString.create(resource);
    this.service = UTF8BytesString.create(service);
    this.operationName = UTF8BytesString.create(operationName);
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
        && operationName.equals(metricKey.operationName);
  }

  @Override
  public int hashCode() {
    return 97 * Objects.hash(resource, service, operationName) + httpStatusCode;
  }
}
