package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Objects;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString operationName;
  private final UTF8BytesString type;
  private final UTF8BytesString dbType;
  private final int httpStatusCode;

  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence type,
      CharSequence dbType,
      int httpStatusCode) {
    this.resource = UTF8BytesString.create(null == resource ? "" : resource);
    this.service = UTF8BytesString.create(null == service ? "" : service);
    this.operationName = UTF8BytesString.create(null == operationName ? "" : operationName);
    this.type = UTF8BytesString.create(null == type ? "" : type);
    this.dbType = UTF8BytesString.create(null == dbType ? "" : dbType);
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

  public UTF8BytesString getDbType() {
    return dbType;
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
        && type.equals(metricKey.type)
        && dbType.equals(metricKey.dbType);
  }

  @Override
  public int hashCode() {
    return 97 * Objects.hash(resource, service, operationName, type, dbType) + httpStatusCode;
  }
}
