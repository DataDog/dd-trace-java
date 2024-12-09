package com.datadog.iast;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS;

import com.datadog.iast.model.VulnerabilityBatch;
import com.datadog.iast.overhead.OverheadContext;
import com.datadog.iast.taint.TaintedMap;
import com.datadog.iast.taint.TaintedObjectsMap;
import com.datadog.iast.util.Wrapper;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.taint.TaintedObjects;
import datadog.trace.api.iast.telemetry.IastMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class IastRequestContext implements IastContext, HasMetricCollector {

  static final int MAP_SIZE = TaintedMap.DEFAULT_CAPACITY;

  private final VulnerabilityBatch vulnerabilityBatch;
  private final OverheadContext overheadContext;
  private TaintedObjects taintedObjects;
  @Nullable private Consumer<IastContext> release;
  @Nullable private IastMetricCollector collector;
  @Nullable private volatile String strictTransportSecurity;
  @Nullable private volatile String xContentTypeOptions;
  @Nullable private volatile String xForwardedProto;
  @Nullable private volatile String contentType;
  @Nullable private volatile String authorization;

  /**
   * Use {@link IastRequestContext#IastRequestContext(TaintedObjects)} instead as we require more
   * control over the tainted objects dictionaries
   */
  @Deprecated
  public IastRequestContext() {
    // map without purge (it will be cleared on request end)
    this(TaintedObjectsMap.build(TaintedMap.build(MAP_SIZE)));
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

  @Nullable
  public String getAuthorization() {
    return authorization;
  }

  public void setAuthorization(final String authorization) {
    this.authorization = authorization;
  }

  public OverheadContext getOverheadContext() {
    return overheadContext;
  }

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

  @Override
  public void close() throws IOException {
    if (release != null) {
      release.accept(this);
      release = null;
    }
  }

  public static class Provider extends IastContext.Provider {

    // 16384 buckets: approx 64K
    static final int MAP_SIZE = TaintedMap.DEFAULT_CAPACITY;

    private final Queue<TaintedObjects> pool =
        new ArrayBlockingQueue<>(
            Math.max(
                Config.get().getIastMaxConcurrentRequests(), DEFAULT_IAST_MAX_CONCURRENT_REQUESTS));

    @Nullable
    @Override
    public TaintedObjects resolveTaintedObjects() {
      final IastContext ctx = get();
      return ctx == null ? null : ctx.getTaintedObjects();
    }

    @Override
    public IastContext buildRequestContext() {
      TaintedObjects taintedObjects = pool.poll();
      if (taintedObjects == null) {
        taintedObjects = TaintedObjectsMap.build(TaintedMap.build(MAP_SIZE));
      }
      final IastRequestContext ctx = new IastRequestContext(taintedObjects);
      ctx.release = this::releaseRequestContext;
      return ctx;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void releaseRequestContext(@Nonnull final IastContext context) {
      final IastRequestContext iastCtx = (IastRequestContext) context;

      // reset tainted objects map
      final TaintedObjects taintedObjects = iastCtx.getTaintedObjects();
      taintedObjects.clear();

      // return to pool and update internal ref
      final TaintedObjects unwrapped =
          taintedObjects instanceof Wrapper
              ? ((Wrapper<TaintedObjects>) taintedObjects).unwrap()
              : taintedObjects;
      if (unwrapped != TaintedObjects.NoOp.INSTANCE) {
        pool.offer(unwrapped);
        iastCtx.setTaintedObjects(TaintedObjects.NoOp.INSTANCE);
      }
    }
  }
}
