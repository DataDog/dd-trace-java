package com.datadog.iast.overhead;

import com.datadog.iast.IastRequestContext;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class OverheadController {

  private final int maxConcurrentRequests;
  private final int sampling;

  final AtomicInteger availableRequests;

  final AtomicInteger executedRequests = new AtomicInteger(0);

  final OverheadContext globalContext = new OverheadContext();

  public OverheadController(final Config config, final AgentTaskScheduler taskScheduler) {
    maxConcurrentRequests = config.getIastMaxConcurrentRequests();
    sampling = computeSamplingParameter(config.getIastRequestSampling());
    availableRequests = new AtomicInteger(maxConcurrentRequests);
    if (taskScheduler != null) {
      taskScheduler.scheduleAtFixedRate(this::reset, 60, 60, TimeUnit.SECONDS);
    }
  }

  public boolean acquireRequest() {
    if (executedRequests.incrementAndGet() % sampling != 0) {
      // Skipped by sampling
      return false;
    }
    if (availableRequests.get() <= 0) {
      return false;
    }
    final int beforeUpdate = availableRequests.getAndUpdate(x -> (x > 0) ? x - 1 : x);
    return beforeUpdate >= 1;
  }

  public void releaseRequest() {
    availableRequests.updateAndGet(x -> (x < maxConcurrentRequests) ? x + 1 : x);
  }

  public boolean hasQuota(final Operation operation, final AgentSpan span) {
    return operation.hasQuota(getContext(span));
  }

  public boolean consumeQuota(final Operation operation, final AgentSpan span) {
    return operation.consumeQuota(getContext(span));
  }

  public OverheadContext getContext(AgentSpan span) {
    final RequestContext requestContext = span != null ? span.getRequestContext() : null;
    if (requestContext != null) {
      IastRequestContext iastRequestContext = requestContext.getData(RequestContextSlot.IAST);
      return iastRequestContext != null ? iastRequestContext.getOverheadContext() : null;
    }
    return globalContext;
  }

  static int computeSamplingParameter(final float pct) {
    if (pct >= 100) {
      return 1;
    }
    if (pct <= 0) {
      // We don't support disabling IAST by setting it, so we set it to 100%.
      // TODO: We probably want a warning here.
      return 1;
    }
    return Math.round(100 / pct);
  }

  public void reset() {
    globalContext.reset();
    // Periodic reset of maximum concurrent requests. This guards us against exhausting concurrent
    // requests if some bug led us to lose a request end event. This will lead to periodically
    // going above the max concurrent requests. But overall, it should be self-stabilizing. So for
    // practical purposes, the max concurrent requests is a hint.
    availableRequests.set(maxConcurrentRequests);
  }
}
