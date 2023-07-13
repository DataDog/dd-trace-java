package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString operationName;
  private final UTF8BytesString type;
  private final UTF8BytesString kind;
  private final UTF8BytesString peerService;
  private final int httpStatusCode;
  private final boolean synthetics;
  private final int hash;

  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence type,
      CharSequence kind,
      CharSequence peerService,
      int httpStatusCode,
      boolean synthetics) {
    this.resource = null == resource ? EMPTY : UTF8BytesString.create(resource);
    this.service = null == service ? EMPTY : UTF8BytesString.create(service);
    this.operationName = null == operationName ? EMPTY : UTF8BytesString.create(operationName);
    this.type = null == type ? EMPTY : UTF8BytesString.create(type);
    this.kind = null == kind ? EMPTY : UTF8BytesString.create(kind);
    this.peerService = null == kind ? EMPTY : UTF8BytesString.create(peerService);
    this.httpStatusCode = httpStatusCode;
    this.synthetics = synthetics;
    // unrolled polynomial hashcode which avoids allocating varargs
    // the constants are 19^7, 19^6, 19^5, 19^4, 19^3, 19^2, 19^1, 19^0
    this.hash =
        893871739 * this.peerService.hashCode()
            + 47045881 * this.kind.hashCode()
            + 2476099 * this.resource.hashCode()
            + 130321 * this.service.hashCode()
            + 6859 * this.operationName.hashCode()
            + 361 * this.type.hashCode()
            + 19 * httpStatusCode
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

  public UTF8BytesString getKind() {
    return kind;
  }

  public UTF8BytesString getPeerService() {
    return peerService;
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
          && type.equals(metricKey.type)
          && kind.equals(metricKey.kind)
          && peerService.equals(metricKey.peerService);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
