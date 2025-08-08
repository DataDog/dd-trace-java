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
    this.resource = null == resource ? EMPTY : UTF8BytesString.create(resource);
    this.service = null == service ? EMPTY : UTF8BytesString.create(service);
    this.operationName = null == operationName ? EMPTY : UTF8BytesString.create(operationName);
    this.type = null == type ? EMPTY : UTF8BytesString.create(type);
    this.httpStatusCode = httpStatusCode;
    this.synthetics = synthetics;
    this.isTraceRoot = isTraceRoot;
    this.spanKind = null == spanKind ? EMPTY : UTF8BytesString.create(spanKind);
    this.peerTags = peerTags == null ? Collections.emptyList() : peerTags;

    // Unrolled polynomial hashcode to avoid varargs allocation
    // and eliminate data dependency between iterations as in Arrays.hashCode.
    // Coefficient constants are powers of 31, with integer overflow (hence negative numbers).
    // See
    // https://richardstartin.github.io/posts/collecting-rocks-and-benchmarks
    // https://richardstartin.github.io/posts/still-true-in-java-9-handwritten-hash-codes-are-faster

    this.hash =
        -196513505 * Boolean.hashCode(this.isTraceRoot)
            + -1807454463 * this.spanKind.hashCode()
            + 887_503_681 * this.peerTags.hashCode() // possibly unroll here has well.
            + 28_629_151 * this.resource.hashCode()
            + 923_521 * this.service.hashCode()
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

  public boolean isTraceRoot() {
    return isTraceRoot;
  }

  public UTF8BytesString getSpanKind() {
    return spanKind;
  }

  public List<UTF8BytesString> getPeerTags() {
    return peerTags;
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
          && peerTags.equals(metricKey.peerTags);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return hash;
  }
}
