package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.HashingUtils;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
  private final UTF8BytesString serviceSource;
  private final UTF8BytesString operationName;
  private final UTF8BytesString type;
  private final int httpStatusCode;
  private final boolean synthetics;
  private final int hash;
  private final boolean isTraceRoot;
  private final UTF8BytesString spanKind;
  private final List<UTF8BytesString> peerTags;
  private final UTF8BytesString httpMethod;
  private final UTF8BytesString httpEndpoint;

  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence serviceSource,
      CharSequence type,
      int httpStatusCode,
      boolean synthetics,
      boolean isTraceRoot,
      CharSequence spanKind,
      List<UTF8BytesString> peerTags,
      CharSequence httpMethod,
      CharSequence httpEndpoint) {
    this.resource = null == resource ? EMPTY : UTF8BytesString.create(resource);
    this.service = null == service ? EMPTY : UTF8BytesString.create(service);
    this.serviceSource = null == serviceSource ? null : UTF8BytesString.create(serviceSource);
    this.operationName = null == operationName ? EMPTY : UTF8BytesString.create(operationName);
    this.type = null == type ? EMPTY : UTF8BytesString.create(type);
    this.httpStatusCode = httpStatusCode;
    this.synthetics = synthetics;
    this.isTraceRoot = isTraceRoot;
    this.spanKind = null == spanKind ? EMPTY : UTF8BytesString.create(spanKind);
    this.peerTags = peerTags == null ? Collections.emptyList() : peerTags;
    this.httpMethod = httpMethod == null ? null : UTF8BytesString.create(httpMethod);
    this.httpEndpoint = httpEndpoint == null ? null : UTF8BytesString.create(httpEndpoint);

    int tmpHash = 0;
    tmpHash = HashingUtils.addToHash(tmpHash, this.isTraceRoot);
    tmpHash = HashingUtils.addToHash(tmpHash, this.spanKind);
    tmpHash = HashingUtils.addToHash(tmpHash, this.peerTags);
    tmpHash = HashingUtils.addToHash(tmpHash, this.resource);
    tmpHash = HashingUtils.addToHash(tmpHash, this.service);
    tmpHash = HashingUtils.addToHash(tmpHash, this.operationName);
    tmpHash = HashingUtils.addToHash(tmpHash, this.type);
    tmpHash = HashingUtils.addToHash(tmpHash, this.httpStatusCode);
    tmpHash = HashingUtils.addToHash(tmpHash, this.synthetics);
    tmpHash = HashingUtils.addToHash(tmpHash, this.serviceSource);
    tmpHash = HashingUtils.addToHash(tmpHash, this.httpEndpoint);
    tmpHash = HashingUtils.addToHash(tmpHash, this.httpMethod);
    this.hash = tmpHash;
  }

  public UTF8BytesString getResource() {
    return resource;
  }

  public UTF8BytesString getService() {
    return service;
  }

  public UTF8BytesString getServiceSource() {
    return serviceSource;
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

  public boolean isTraceRoot() {
    return isTraceRoot;
  }

  public UTF8BytesString getSpanKind() {
    return spanKind;
  }

  public List<UTF8BytesString> getPeerTags() {
    return peerTags;
  }

  public UTF8BytesString getHttpMethod() {
    return httpMethod;
  }

  public UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
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
          && isTraceRoot == metricKey.isTraceRoot
          && spanKind.equals(metricKey.spanKind)
          && peerTags.equals(metricKey.peerTags)
          && Objects.equals(serviceSource, metricKey.serviceSource)
          && Objects.equals(httpMethod, metricKey.httpMethod)
          && Objects.equals(httpEndpoint, metricKey.httpEndpoint);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
