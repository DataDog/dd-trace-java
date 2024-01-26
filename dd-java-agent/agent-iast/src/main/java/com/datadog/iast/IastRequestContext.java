package com.datadog.iast;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;

import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.overhead.OverheadContext;
import com.datadog.iast.taint.TaintedMap;
import com.datadog.iast.taint.TaintedObjects;
import com.datadog.iast.util.Wrapper;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastRequestContext implements IastContext, HasMetricCollector {

  static final int MAP_SIZE = TaintedMap.DEFAULT_CAPACITY;

  private final VulnerabilityBatch vulnerabilityBatch;
  private final OverheadContext overheadContext;
  private TaintedObjects taintedObjects;
  @Nullable private IastMetricCollector collector;
  @Nullable private volatile String strictTransportSecurity;
  @Nullable private volatile String xContentTypeOptions;
  @Nullable private volatile String xForwardedProto;
  @Nullable private volatile String contentType;

  /**
   * Use {@link IastRequestContext#IastRequestContext(TaintedObjects)} instead as we require more
   * control over the tainted objects dictionaries
   */
  @Deprecated
  public IastRequestContext() {
    // map without purge (it will be cleared on request end)
    this(TaintedObjects.build(TaintedMap.build(MAP_SIZE)));
  }

  public IastRequestContext(final TaintedObjects taintedObjects) {
    this.vulnerabilityBatch = new VulnerabilityBatch();
    this.overheadContext = new OverheadContext(Config.get().getIastVulnerabilitiesPerRequest());
    this.taintedObjects = taintedObjects;
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

  public void setCollector(@Nonnull final IastMetricCollector collector) {
    this.collector = collector;
  }

  public void setTaintedObjects(@Nonnull final TaintedObjects taintedObjects) {
    this.taintedObjects = taintedObjects;
  }

  public static class Provider extends IastContext.Provider {

    // 16384 buckets: approx 64K
    static final int MAP_SIZE = TaintedMap.DEFAULT_CAPACITY;

    final Queue<TaintedObjects> pool =
        new ArrayBlockingQueue<>(
            Math.max(
                Config.get().getIastMaxConcurrentRequests(), DEFAULT_IAST_MAX_CONCURRENT_REQUESTS));

    @Nullable
    @Override
    public IastContext resolve() {
      final AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        return null;
      }
      final RequestContext ctx = span.getRequestContext();
      if (ctx == null) {
        return null;
      }
      return ctx.getData(RequestContextSlot.IAST);
    }

    @Override
    public IastContext buildRequestContext() {
      TaintedObjects taintedObjects = pool.poll();
      if (taintedObjects == null) {
        taintedObjects = TaintedObjects.build(TaintedMap.build(MAP_SIZE));
      }
      return new IastRequestContext(taintedObjects);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void releaseRequestContext(@Nonnull final IastContext context) {
      final TaintedObjects taintedObjects = context.getTaintedObjects();
      taintedObjects.clear();
      // add the root instance to the pool
      if (taintedObjects instanceof Wrapper) {
        pool.offer(((Wrapper<TaintedObjects>) taintedObjects).unwrap());
      } else {
        pool.offer(taintedObjects);
      }
    }
  }
}
