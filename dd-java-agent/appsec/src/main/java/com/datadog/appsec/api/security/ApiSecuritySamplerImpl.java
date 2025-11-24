package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.Config;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiSecuritySamplerImpl implements ApiSecuritySampler {

  private static final Logger log = LoggerFactory.getLogger(ApiSecuritySamplerImpl.class);

  /**
   * A maximum number of request contexts we'll keep open past the end of request at any given time.
   * This will avoid excessive memory usage in case of a high number of concurrent requests, and
   * should also prevent memory leaks.
   */
  private static final int MAX_POST_PROCESSING_TASKS = 4;
  /** Maximum number of entries in the access map. */
  private static final int MAX_SIZE = 4096;
  /** Mapping from endpoint hash to last access timestamp in millis. */
  private final ConcurrentHashMap<Long, Long> accessMap;
  /** Deque of endpoint hashes ordered by access time. Oldest is always first. */
  private final Deque<Long> accessDeque;

  private final long expirationTimeInMs;
  private final int capacity;
  private final TimeSource timeSource;
  private final Semaphore counter = new Semaphore(MAX_POST_PROCESSING_TASKS);

  public ApiSecuritySamplerImpl() {
    this(
        MAX_SIZE,
        (long) (Config.get().getApiSecuritySampleDelay() * 1_000),
        SystemTimeSource.INSTANCE);
  }

  public ApiSecuritySamplerImpl(
      int capacity, long expirationTimeInMs, @Nonnull TimeSource timeSource) {
    this.capacity = capacity;
    this.expirationTimeInMs = expirationTimeInMs;
    this.accessMap = new ConcurrentHashMap<>();
    this.accessDeque = new ConcurrentLinkedDeque<>();
    this.timeSource = timeSource;
  }

  @Override
  public boolean preSampleRequest(final @Nonnull AppSecRequestContext ctx) {
    final String route = ctx.getRoute();
    if (route == null) {
      log.info("[APPSEC-57815] preSampleRequest returning false - route is null");
      return false;
    }
    final String method = ctx.getMethod();
    if (method == null) {
      log.info("[APPSEC-57815] preSampleRequest returning false - method is null, route={}", route);
      return false;
    }
    final int statusCode = ctx.getResponseStatus();
    if (statusCode <= 0) {
      log.info(
          "[APPSEC-57815] preSampleRequest returning false - invalid statusCode={}, route={}, method={}",
          statusCode,
          route,
          method);
      return false;
    }
    long hash = computeApiHash(route, method, statusCode);
    ctx.setApiSecurityEndpointHash(hash);

    boolean isExpired = isApiAccessExpired(hash);
    log.info(
        "[APPSEC-57815] preSampleRequest checking expiration - route={}, method={}, statusCode={}, hash={}, isExpired={}, expirationTimeMs={}",
        route,
        method,
        statusCode,
        hash,
        isExpired,
        expirationTimeInMs);

    if (!isExpired) {
      return false;
    }

    int availablePermits = counter.availablePermits();
    boolean acquired = counter.tryAcquire();
    log.info(
        "[APPSEC-57815] preSampleRequest semaphore check - availablePermits={}, acquired={}, route={}",
        availablePermits,
        acquired,
        route);

    if (acquired) {
      log.debug("API security sampling is required for this request (presampled)");
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
      return true;
    }
    return false;
  }

  /** Get the final sampling decision. This method is NOT thread-safe. */
  @Override
  public boolean sampleRequest(AppSecRequestContext ctx) {
    if (ctx == null) {
      return false;
    }
    final Long hash = ctx.getApiSecurityEndpointHash();
    if (hash == null) {
      // This should never happen, it should have been short-circuited before.
      return false;
    }
    return updateApiAccessIfExpired(hash);
  }

  @Override
  public void releaseOne() {
    counter.release();
  }

  private boolean updateApiAccessIfExpired(final long hash) {
    final long currentTime = timeSource.getCurrentTimeMillis();

    Long lastAccess = accessMap.get(hash);
    if (lastAccess != null && currentTime - lastAccess < expirationTimeInMs) {
      return false;
    }

    if (accessMap.put(hash, currentTime) == null) {
      accessDeque.addLast(hash);
      // If we added a new entry, we perform purging.
      cleanupExpiredEntries(currentTime);
    } else {
      // This is now the most recently accessed entry.
      accessDeque.remove(hash);
      accessDeque.addLast(hash);
    }

    return true;
  }

  private boolean isApiAccessExpired(final long hash) {
    final long currentTime = timeSource.getCurrentTimeMillis();
    final Long lastAccess = accessMap.get(hash);
    return lastAccess == null || currentTime - lastAccess >= expirationTimeInMs;
  }

  private void cleanupExpiredEntries(final long currentTime) {
    // Purge all expired entries.
    while (!accessDeque.isEmpty()) {
      final Long oldestHash = accessDeque.peekFirst();
      if (oldestHash == null) {
        // Should never happen
        continue;
      }

      final Long lastAccessTime = accessMap.get(oldestHash);
      if (lastAccessTime == null) {
        // Should never  happen
        continue;
      }

      if (currentTime - lastAccessTime < expirationTimeInMs) {
        // The oldest hash is up-to-date, so stop here.
        break;
      }

      accessDeque.pollFirst();
      accessMap.remove(oldestHash);
    }

    // If we went over capacity, remove the oldest entries until we are within the limit.
    // This should never be more than 1.
    final int toRemove = accessMap.size() - this.capacity;
    for (int i = 0; i < toRemove; i++) {
      Long oldestHash = accessDeque.pollFirst();
      if (oldestHash != null) {
        accessMap.remove(oldestHash);
      }
    }
  }

  private long computeApiHash(final String route, final String method, final int statusCode) {
    long result = 17;
    result = 31 * result + route.hashCode();
    result = 31 * result + method.hashCode();
    result = 31 * result + statusCode;
    return result;
  }
}
