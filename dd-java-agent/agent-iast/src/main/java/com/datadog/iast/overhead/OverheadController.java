package com.datadog.iast.overhead;

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.IastSystem;
import com.datadog.iast.util.NonBlockingSemaphore;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface OverheadController {

  boolean acquireRequest();

  void reset();

  int releaseRequest();

  boolean hasQuota(final Operation operation, final AgentSpan span);

  boolean consumeQuota(final Operation operation, final AgentSpan span);

  static OverheadController build(final Config config, final AgentTaskScheduler scheduler) {
    final OverheadControllerImpl result = new OverheadControllerImpl(config, scheduler);
    return IastSystem.DEBUG ? new OverheadControllerDebugAdapter(result) : result;
  }

  class OverheadControllerDebugAdapter implements OverheadController {

    static Logger LOGGER = LoggerFactory.getLogger(OverheadController.class);

    private final OverheadControllerImpl delegate;

    public OverheadControllerDebugAdapter(final OverheadControllerImpl delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean acquireRequest() {
      final boolean result = delegate.acquireRequest();
      if (LOGGER.isDebugEnabled()) {
        final int available = delegate.availableRequests.available();
        LOGGER.debug(
            "acquireRequest: acquired={}, availableRequests={}, span={}",
            result,
            available,
            AgentTracer.activeSpan());
      }
      return result;
    }

    @Override
    public int releaseRequest() {
      int result = delegate.releaseRequest();
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(
            "releaseRequest: availableRequests={}, span={}", result, AgentTracer.activeSpan());
      }
      return result;
    }

    @Override
    public boolean hasQuota(final Operation operation, final AgentSpan span) {
      final boolean result = delegate.hasQuota(operation, span);
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(
            "hasQuota: operation={}, result={}, availableQuota={}, span={}",
            operation,
            result,
            getAvailableQuote(span),
            span);
      }
      return result;
    }

    @Override
    public boolean consumeQuota(final Operation operation, final AgentSpan span) {
      final boolean result = delegate.consumeQuota(operation, span);
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug(
            "consumeQuota: operation={}, result={}, availableQuota={}, span={}",
            operation,
            result,
            getAvailableQuote(span),
            span);
      }
      return result;
    }

    @Override
    public void reset() {
      delegate.reset();
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("reset: span={}", AgentTracer.activeSpan());
      }
    }

    private int getAvailableQuote(final AgentSpan span) {
      final OverheadContext context = delegate.getContext(span);
      return context == null ? -1 : context.getAvailableQuota();
    }
  }

  class OverheadControllerImpl implements OverheadController {

    private static final int RESET_PERIOD_SECONDS = 30;

    private final int sampling;

    final NonBlockingSemaphore availableRequests;

    final AtomicLong cumulativeCounter;

    final OverheadContext globalContext = new OverheadContext();

    public OverheadControllerImpl(final Config config, final AgentTaskScheduler taskScheduler) {
      sampling = computeSamplingParameter(config.getIastRequestSampling());
      availableRequests = maxConcurrentRequests(config.getIastMaxConcurrentRequests());
      cumulativeCounter = new AtomicLong(sampling);
      if (taskScheduler != null) {
        taskScheduler.scheduleAtFixedRate(
            this::reset, 2 * RESET_PERIOD_SECONDS, RESET_PERIOD_SECONDS, TimeUnit.SECONDS);
      }
    }

    @Override
    public boolean acquireRequest() {
      long prevValue = cumulativeCounter.getAndAdd(sampling);
      long newValue = prevValue + sampling;
      if (newValue / 100 == prevValue / 100 + 1) {
        // Sample request
        return availableRequests.acquire();
      }
      // Skipped by sampling
      return false;
    }

    @Override
    public int releaseRequest() {
      return availableRequests.release();
    }

    @Override
    public boolean hasQuota(final Operation operation, final AgentSpan span) {
      return operation.hasQuota(getContext(span));
    }

    @Override
    public boolean consumeQuota(final Operation operation, final AgentSpan span) {
      return operation.consumeQuota(getContext(span));
    }

    public OverheadContext getContext(final AgentSpan span) {
      final RequestContext requestContext = span != null ? span.getRequestContext() : null;
      if (requestContext != null) {
        IastRequestContext iastRequestContext = requestContext.getData(RequestContextSlot.IAST);
        return iastRequestContext != null ? iastRequestContext.getOverheadContext() : null;
      }
      return globalContext;
    }

    static int computeSamplingParameter(final float pct) {
      if (pct >= 100) {
        return 100;
      }
      if (pct <= 0) {
        // We don't support disabling IAST by setting it, so we set it to 100%.
        // TODO: We probably want a warning here.
        return 100;
      }
      return (int) pct;
    }

    static NonBlockingSemaphore maxConcurrentRequests(final int max) {
      return max == UNLIMITED
          ? NonBlockingSemaphore.unlimited()
          : NonBlockingSemaphore.withPermitCount(max);
    }

    @Override
    public void reset() {
      globalContext.reset();
      // Periodic reset of maximum concurrent requests. This guards us against exhausting concurrent
      // requests if some bug led us to lose a request end event. This will lead to periodically
      // going above the max concurrent requests. But overall, it should be self-stabilizing. So for
      // practical purposes, the max concurrent requests is a hint.
      availableRequests.reset();
    }
  }
}
