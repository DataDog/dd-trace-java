package datadog.trace.common.metrics;

import static datadog.trace.bootstrap.instrumentation.api.UTF8BytesString.EMPTY;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Collections;
import java.util.List;

/** The aggregation key for tracked metrics. */
public final class MetricKey {
  private final UTF8BytesString resource;
  private final UTF8BytesString service;
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

  // Constructor without httpMethod and httpEndpoint for backward compatibility
  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence type,
      int httpStatusCode,
      boolean synthetics,
      boolean isTraceRoot,
      CharSequence spanKind,
      List<UTF8BytesString> peerTags) {
    this(
        resource,
        service,
        operationName,
        type,
        httpStatusCode,
        synthetics,
        isTraceRoot,
        spanKind,
        peerTags,
        null,
        null);
  }

  public MetricKey(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
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
    this.operationName = null == operationName ? EMPTY : UTF8BytesString.create(operationName);
    this.type = null == type ? EMPTY : UTF8BytesString.create(type);
    this.httpStatusCode = httpStatusCode;
    this.synthetics = synthetics;
    this.isTraceRoot = isTraceRoot;
    this.spanKind = null == spanKind ? EMPTY : UTF8BytesString.create(spanKind);
    this.peerTags = peerTags == null ? Collections.emptyList() : peerTags;
    this.httpMethod = null == httpMethod ? EMPTY : UTF8BytesString.create(httpMethod);
    this.httpEndpoint = null == httpEndpoint ? EMPTY : UTF8BytesString.create(httpEndpoint);

    // Unrolled polynomial hashcode to avoid varargs allocation
    // and eliminate data dependency between iterations as in Arrays.hashCode.
    // Coefficient constants are powers of 31, with integer overflow (hence negative numbers).
    // See
    // https://richardstartin.github.io/posts/collecting-rocks-and-benchmarks
    // https://richardstartin.github.io/posts/still-true-in-java-9-handwritten-hash-codes-are-faster

    // Only include httpMethod and httpEndpoint in hash if they are not EMPTY
    // This ensures backward compatibility when the feature is disabled
    boolean includeEndpointInHash =
        !this.httpMethod.equals(EMPTY) || !this.httpEndpoint.equals(EMPTY);

    this.hash =
        -196_513_505 * Boolean.hashCode(this.isTraceRoot)
            + -1_807_454_463 * this.spanKind.hashCode()
            + 887_503_681 * this.peerTags.hashCode()
            + (includeEndpointInHash ? 28_629_151 * this.httpMethod.hashCode() : 0)
            + (includeEndpointInHash ? 923_521 * this.httpEndpoint.hashCode() : 0)
            + 29_791 * this.resource.hashCode()
            + 961 * this.service.hashCode()
            + 31 * this.operationName.hashCode()
            + this.type.hashCode()
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
      boolean basicEquals =
          hash == metricKey.hash
              && synthetics == metricKey.synthetics
              && httpStatusCode == metricKey.httpStatusCode
              && resource.equals(metricKey.resource)
              && service.equals(metricKey.service)
              && operationName.equals(metricKey.operationName)
              && type.equals(metricKey.type)
              && isTraceRoot == metricKey.isTraceRoot
              && spanKind.equals(metricKey.spanKind)
              && peerTags.equals(metricKey.peerTags);

      // Only compare httpMethod and httpEndpoint if at least one of them is not EMPTY
      // This ensures backward compatibility when the feature is disabled
      boolean thisHasEndpoint = !httpMethod.equals(EMPTY) || !httpEndpoint.equals(EMPTY);
      boolean otherHasEndpoint =
          !metricKey.httpMethod.equals(EMPTY) || !metricKey.httpEndpoint.equals(EMPTY);

      if (thisHasEndpoint || otherHasEndpoint) {
        return basicEquals
            && httpMethod.equals(metricKey.httpMethod)
            && httpEndpoint.equals(metricKey.httpEndpoint);
      }
      return basicEquals;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
