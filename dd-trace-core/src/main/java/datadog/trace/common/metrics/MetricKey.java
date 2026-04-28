package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.HashingUtils;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  static final DDCache<String, UTF8BytesString> RESOURCE_CACHE = DDCaches.newFixedSizeCache(32);
  static final DDCache<String, UTF8BytesString> SERVICE_CACHE = DDCaches.newFixedSizeCache(8);
  static final DDCache<String, UTF8BytesString> SERVICE_SOURCE_CACHE =
      DDCaches.newFixedSizeCache(16);
  static final DDCache<String, UTF8BytesString> OPERATION_CACHE = DDCaches.newFixedSizeCache(64);
  static final DDCache<String, UTF8BytesString> TYPE_CACHE = DDCaches.newFixedSizeCache(8);
  static final DDCache<String, UTF8BytesString> KIND_CACHE = DDCaches.newFixedSizeCache(8);
  static final DDCache<String, UTF8BytesString> HTTP_METHOD_CACHE = DDCaches.newFixedSizeCache(8);
  static final DDCache<String, UTF8BytesString> HTTP_ENDPOINT_CACHE =
      DDCaches.newFixedSizeCache(32);
  static final DDCache<String, UTF8BytesString> GRPC_STATUS_CODE_CACHE =
      DDCaches.newFixedSizeCache(32);

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
  private final List<UTF8BytesString> additionalMetricTags;
  private final UTF8BytesString httpMethod;
  private final UTF8BytesString httpEndpoint;
  private final UTF8BytesString grpcStatusCode;

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
      CharSequence httpEndpoint,
      CharSequence grpcStatusCode) {
    this(
        resource,
        service,
        operationName,
        serviceSource,
        type,
        httpStatusCode,
        synthetics,
        isTraceRoot,
        spanKind,
        peerTags,
        Collections.emptyList(),
        httpMethod,
        httpEndpoint,
        grpcStatusCode);
  }

  // TODO: Should we keep one constructor? We'd need to refactor all of the old calls.
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
      List<UTF8BytesString> additionalMetricTags,
      CharSequence httpMethod,
      CharSequence httpEndpoint,
      CharSequence grpcStatusCode) {
    this.resource = null == resource ? EMPTY : utf8(RESOURCE_CACHE, resource);
    this.service = null == service ? EMPTY : utf8(SERVICE_CACHE, service);
    this.serviceSource = null == serviceSource ? null : utf8(SERVICE_SOURCE_CACHE, serviceSource);
    this.operationName = null == operationName ? EMPTY : utf8(OPERATION_CACHE, operationName);
    this.type = null == type ? EMPTY : utf8(TYPE_CACHE, type);
    this.httpStatusCode = httpStatusCode;
    this.synthetics = synthetics;
    this.isTraceRoot = isTraceRoot;
    this.spanKind = null == spanKind ? EMPTY : utf8(KIND_CACHE, spanKind);
    this.peerTags = peerTags == null ? Collections.emptyList() : peerTags;
    this.additionalMetricTags =
        additionalMetricTags == null ? Collections.emptyList() : additionalMetricTags;
    this.httpMethod = httpMethod == null ? null : utf8(HTTP_METHOD_CACHE, httpMethod);
    this.httpEndpoint = httpEndpoint == null ? null : utf8(HTTP_ENDPOINT_CACHE, httpEndpoint);
    this.grpcStatusCode =
        grpcStatusCode == null ? null : utf8(GRPC_STATUS_CODE_CACHE, grpcStatusCode);

    int tmpHash = 0;
    tmpHash = HashingUtils.addToHash(tmpHash, this.isTraceRoot);
    tmpHash = HashingUtils.addToHash(tmpHash, this.spanKind);
    tmpHash = HashingUtils.addToHash(tmpHash, this.peerTags);
    tmpHash = HashingUtils.addToHash(tmpHash, this.additionalMetricTags);
    tmpHash = HashingUtils.addToHash(tmpHash, this.resource);
    tmpHash = HashingUtils.addToHash(tmpHash, this.service);
    tmpHash = HashingUtils.addToHash(tmpHash, this.operationName);
    tmpHash = HashingUtils.addToHash(tmpHash, this.type);
    tmpHash = HashingUtils.addToHash(tmpHash, this.httpStatusCode);
    tmpHash = HashingUtils.addToHash(tmpHash, this.synthetics);
    tmpHash = HashingUtils.addToHash(tmpHash, this.serviceSource);
    tmpHash = HashingUtils.addToHash(tmpHash, this.httpEndpoint);
    tmpHash = HashingUtils.addToHash(tmpHash, this.httpMethod);
    tmpHash = HashingUtils.addToHash(tmpHash, this.grpcStatusCode);
    this.hash = tmpHash;
  }

  static UTF8BytesString utf8(DDCache<String, UTF8BytesString> cache, CharSequence charSeq) {
    if (charSeq instanceof UTF8BytesString) {
      return (UTF8BytesString) charSeq;
    } else {
      return cache.computeIfAbsent(charSeq.toString(), UTF8BytesString::create);
    }
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

  public List<UTF8BytesString> getAdditionalMetricTags() {
    return additionalMetricTags;
  }

  public UTF8BytesString getHttpMethod() {
    return httpMethod;
  }

  public UTF8BytesString getHttpEndpoint() {
    return httpEndpoint;
  }

  public UTF8BytesString getGrpcStatusCode() {
    return grpcStatusCode;
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
          && additionalMetricTags.equals(metricKey.additionalMetricTags)
          && Objects.equals(serviceSource, metricKey.serviceSource)
          && Objects.equals(httpMethod, metricKey.httpMethod)
          && Objects.equals(httpEndpoint, metricKey.httpEndpoint)
          && Objects.equals(grpcStatusCode, metricKey.grpcStatusCode);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
