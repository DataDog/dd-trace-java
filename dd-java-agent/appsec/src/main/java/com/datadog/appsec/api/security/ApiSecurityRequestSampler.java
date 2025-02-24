package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.util.NonBlockingSemaphore;

import javax.annotation.Nonnull;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ApiSecurityRequestSampler {

  /**
   * A maximum number of request contexts we'll keep open past the end of request at any given time. This will avoid
   * excessive memory usage in case of a high number of concurrent requests, and should also prevent memory leaks in
   * case of a bug.
   */
  private static final int MAX_POST_PROCESSING_TASKS = 4;
  private static final int INTERVAL_SECONDS = 30;
  private static final int MAX_SIZE = 4096;
  private final Map<Long, Long> apiAccessMap; // Map<hash, timestamp>
  private final Deque<Long> apiAccessQueue; // hashes ordered by access time
  private final long expirationTimeInMs;
  private final int capacity;

  final NonBlockingSemaphore counter = NonBlockingSemaphore.withPermitCount(MAX_POST_PROCESSING_TASKS);

  public ApiSecurityRequestSampler() {
    this(MAX_SIZE, INTERVAL_SECONDS * 1000);
  }

  public ApiSecurityRequestSampler(int capacity, long expirationTimeInMs) {
    this.capacity = capacity;
    this.expirationTimeInMs = expirationTimeInMs;
    this.apiAccessMap = new ConcurrentHashMap<>(MAX_SIZE);
    this.apiAccessQueue = new ConcurrentLinkedDeque<>();
  }

  public void preSampleRequest(final @Nonnull AppSecRequestContext ctx) {
    final String route = ctx.getRoute();
    if (route == null) {
      return;
    }
    final String method = ctx.getMethod();
    if (method == null) {
      return;
    }
    final int statusCode = ctx.getResponseStatus();
    if (statusCode == 0) {
      return;
    }
    long hash = computeApiHash(route, method, statusCode);
    ctx.setApiSecurityEndpointHash(hash);
    if (!isApiAccessExpired(hash)) {
      return;
    }
    if (counter.acquire()) {
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
    }
  }

  public boolean sampleRequest(AppSecRequestContext ctx) {
    if (ctx == null) {
      return false;
    }
    final Long hash = ctx.getApiSecurityEndpointHash();
    if (hash == null) {
      return false;
    }
    return updateApiAccessIfExpired(hash);
  }

  /**
   * Updates the API access log with the given route, method, and status code. If the record already
   * exists and is outdated, it is updated by moving to the end of the list. If the record does not
   * exist, a new record is added. If the capacity limit is reached, the oldest record is removed.
   * This method should not be called concurrently by multiple threads, due absence of additional
   * synchronization for updating data structures is not required.
   */
  public boolean updateApiAccessIfExpired(final long hash) {
    final long currentTime = System.currentTimeMillis();

    // New or updated record
    boolean isNewOrUpdated = false;
    if (!apiAccessMap.containsKey(hash)
        || currentTime - apiAccessMap.get(hash) > expirationTimeInMs) {

      cleanupExpiredEntries(currentTime);

      apiAccessMap.put(hash, currentTime); // Update timestamp
      // move hash to the end of the queue
      apiAccessQueue.remove(hash);
      apiAccessQueue.addLast(hash);
      isNewOrUpdated = true;

      // Remove the oldest hash if capacity is reached
      while (apiAccessMap.size() > this.capacity) {
        Long oldestHash = apiAccessQueue.pollFirst();
        if (oldestHash != null) {
          apiAccessMap.remove(oldestHash);
        }
      }
    }

    return isNewOrUpdated;
  }

  public boolean isApiAccessExpired(final long hash) {
    long currentTime = System.currentTimeMillis();
    return !apiAccessMap.containsKey(hash)
        || currentTime - apiAccessMap.get(hash) > expirationTimeInMs;
  }

  private void cleanupExpiredEntries(final long currentTime) {
    while (!apiAccessQueue.isEmpty()) {
      Long oldestHash = apiAccessQueue.peekFirst();
      if (oldestHash == null) break;

      Long lastAccessTime = apiAccessMap.get(oldestHash);
      if (lastAccessTime == null || currentTime - lastAccessTime > expirationTimeInMs) {
        apiAccessQueue.pollFirst(); // remove from head
        apiAccessMap.remove(oldestHash);
      } else {
        break; // is up-to-date
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

  public static final class NoOp extends ApiSecurityRequestSampler {
    public NoOp() {
      super(0, 0);
    }

    @Override
    public void preSampleRequest(@Nonnull AppSecRequestContext ctx) {
    }

    @Override
    public boolean sampleRequest(AppSecRequestContext ctx) {
      return false;
    }
  }

}
