package com.datadog.iast.overhead;

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.IastSystem;
import com.datadog.iast.util.NonBlockingSemaphore;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.telemetry.LogCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.AgentTaskScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface OverheadController {

  boolean acquireRequest();

  void reset();

  int releaseRequest();

  boolean hasQuota(final Operation operation, @Nullable final AgentSpan span);

  boolean consumeQuota(final Operation operation, @Nullable final AgentSpan span);

  static OverheadController build(final Config config, final AgentTaskScheduler scheduler) {
    return build(
        config.getIastRequestSampling(),
        config.getIastMaxConcurrentRequests(),
        config.getIastContextMode() == IastContext.Mode.GLOBAL,
        scheduler);
  }

  static OverheadController build(
      final float requestSampling,
      final int maxConcurrentRequests,
      final boolean globalFallback,
      final AgentTaskScheduler scheduler) {
    final OverheadControllerImpl result =
        new OverheadControllerImpl(
            requestSampling, maxConcurrentRequests, globalFallback, scheduler);
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
    public boolean hasQuota(final Operation operation, @Nullable final AgentSpan span) {
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
    public boolean consumeQuota(final Operation operation, @Nullable final AgentSpan span) {
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

    private int getAvailableQuote(@Nullable final AgentSpan span) {
      final OverheadContext context = delegate.getContext(span);
      return context == null ? -1 : context.getAvailableQuota();
    }
  }

  class OverheadControllerImpl implements OverheadController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverheadControllerImpl.class);

    private static final int RESET_PERIOD_SECONDS = 30;

    private final int sampling;

    /**
     * Fallback to use the global context instance when no IAST context is present in the active
     * span
     */
    private final boolean useGlobalAsFallback;

    final NonBlockingSemaphore availableRequests;

    final AtomicLong cumulativeCounter;

    private volatile long lastAcquiredTimestamp = Long.MAX_VALUE;

    final OverheadContext globalContext =
        new OverheadContext(Config.get().getIastVulnerabilitiesPerRequest());

    public OverheadControllerImpl(
        final float requestSampling,
        final int maxConcurrentRequests,
        final boolean useGlobalAsFallback,
        final AgentTaskScheduler taskScheduler) {
      this.sampling = computeSamplingParameter(requestSampling);
      availableRequests = maxConcurrentRequests(maxConcurrentRequests);
      cumulativeCounter = new AtomicLong(sampling);
      this.useGlobalAsFallback = useGlobalAsFallback;
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
        final boolean acquired = availableRequests.acquire();
        if (acquired) {
          lastAcquiredTimestamp = System.currentTimeMillis();
        }
        return acquired;
      }
      // Skipped by sampling
      return false;
    }

    @Override
    public int releaseRequest() {
      return availableRequests.release();
    }

    @Override
    public boolean hasQuota(final Operation operation, @Nullable final AgentSpan span) {
      return operation.hasQuota(getContext(span));
    }

    @Override
    public boolean consumeQuota(final Operation operation, @Nullable final AgentSpan span) {
      return operation.consumeQuota(getContext(span));
    }

    @Nullable
    public OverheadContext getContext(@Nullable final AgentSpan span) {
      final RequestContext requestContext = span != null ? span.getRequestContext() : null;
      if (requestContext != null) {
        IastRequestContext iastRequestContext = requestContext.getData(RequestContextSlot.IAST);
        if (iastRequestContext != null) {
          return iastRequestContext.getOverheadContext();
        }
        if (!useGlobalAsFallback) {
          return null;
        }
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
      if (lastAcquiredTimestamp != Long.MAX_VALUE
          && System.currentTimeMillis() - lastAcquiredTimestamp > 1000 * 60 * 60) {
        // If the last time a request was acquired is longer than 1h, we check the number of
        // available requests. If it
        // is zero, we might have lost request end events, leading to IAST not being able to acquire
        // new requests.
        // We report it to telemetry for further investigation.
        if (availableRequests.available() == 0) {
          LOGGER.debug(
              LogCollector.SEND_TELEMETRY,
              "IAST cannot acquire new requests, end of request events might be missing.");
          // Once starved, do not report this again, unless this is recovered and then starved
          // again.
          lastAcquiredTimestamp = Long.MAX_VALUE;
        }
      }
    }
  }
}
