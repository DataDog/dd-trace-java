package com.datadog.iast;

import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.overhead.OverheadContext;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastRequestContext implements IastContext, HasMetricCollector {

  private final VulnerabilityBatch vulnerabilityBatch;
  private final AtomicBoolean spanDataIsSet;
  private final TaintedObjects taintedObjects;
  private final OverheadContext overheadContext;
  @Nullable private final IastMetricCollector collector;
  @Nullable private volatile String strictTransportSecurity;
  @Nullable private volatile String xContentTypeOptions;
  @Nullable private volatile String xForwardedProto;
  @Nullable private volatile String contentType;

  public IastRequestContext() {
    this(TaintedObjects.acquire(), null);
  }

  public IastRequestContext(final TaintedObjects taintedObjects) {
    this(taintedObjects, null);
  }

  public IastRequestContext(
      final TaintedObjects taintedObjects, @Nullable final IastMetricCollector collector) {
    this.vulnerabilityBatch = new VulnerabilityBatch();
    this.spanDataIsSet = new AtomicBoolean(false);
    this.overheadContext = new OverheadContext();
    this.taintedObjects = taintedObjects;
    this.collector = collector;
  }

  public VulnerabilityBatch getVulnerabilityBatch() {
    return vulnerabilityBatch;
  }

  @Nullable
  public String getStrictTransportSecurity() {
    return strictTransportSecurity;
  }

  public void setStrictTransportSecurity(final String strictTransportSecurity) {
    this.strictTransportSecurity = strictTransportSecurity;
  }

  @Nullable
  public String getxContentTypeOptions() {
    return xContentTypeOptions;
  }

  public void setxContentTypeOptions(final String xContentTypeOptions) {
    this.xContentTypeOptions = xContentTypeOptions;
  }

  @Nullable
  public String getxForwardedProto() {
    return xForwardedProto;
  }

  public void setxForwardedProto(final String xForwardedProto) {
    this.xForwardedProto = xForwardedProto;
  }

  @Nullable
  public String getContentType() {
    return contentType;
  }

  public void setContentType(final String contentType) {
    this.contentType = contentType;
  }

  public boolean getAndSetSpanDataIsSet() {
    return spanDataIsSet.getAndSet(true);
  }

  public OverheadContext getOverheadContext() {
    return overheadContext;
  }

  @SuppressWarnings("unchecked")
  @Nonnull
  @Override
  public TaintedObjects getTaintedObjects() {
    return taintedObjects;
  }

  @Override
  @Nullable
  public IastMetricCollector getMetricCollector() {
    return collector;
  }
}
