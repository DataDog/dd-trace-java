package com.datadog.appsec.api.security;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.util.NonBlockingSemaphore;

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
    this.apiAccessMap = new ConcurrentHashMap<>();
    this.apiAccessQueue = new ConcurrentLinkedDeque<>();
  }

  public void preSampleRequest(final AppSecRequestContext ctx) {
    if (!isValid(ctx)) {
      return;
    }

    if (!isApiAccessExpired(ctx.getRoute(), ctx.getMethod(), ctx.getResponseStatus())) {
      return;
    }

    if (counter.acquire()) {
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
    }
  }

  public boolean sampleRequest(AppSecRequestContext ctx) {
    if (!isValid(ctx)) {
      return false;
    }

    return updateApiAccessIfExpired(
        ctx.getRoute(), ctx.getMethod(), ctx.getResponseStatus());
  }

  private boolean isValid(AppSecRequestContext ctx) {
    return ctx != null
        && ctx.getRoute() != null
        && ctx.getMethod() != null
        && ctx.getResponseStatus() != 0;
  }

  /**
   * Updates the API access log with the given route, method, and status code. If the record already
   * exists and is outdated, it is updated by moving to the end of the list. If the record does not
   * exist, a new record is added. If the capacity limit is reached, the oldest record is removed.
   * This method should not be called concurrently by multiple threads, due absence of additional
   * synchronization for updating data structures is not required.
   *
   * @param route The route of the API endpoint request
   * @param method The method of the API request
   * @param statusCode The HTTP response status code of the API request
   * @return return true if the record was updated or added, false otherwise
   */
  public boolean updateApiAccessIfExpired(String route, String method, int statusCode) {
    long currentTime = System.currentTimeMillis();
    long hash = computeApiHash(route, method, statusCode);

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

  public boolean isApiAccessExpired(String route, String method, int statusCode) {
    long currentTime = System.currentTimeMillis();
    long hash = computeApiHash(route, method, statusCode);
    return !apiAccessMap.containsKey(hash)
        || currentTime - apiAccessMap.get(hash) > expirationTimeInMs;
  }

  private void cleanupExpiredEntries(long currentTime) {
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

  private long computeApiHash(String route, String method, int statusCode) {
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
    public void preSampleRequest(AppSecRequestContext ctx) {
    }

    @Override
    public boolean sampleRequest(AppSecRequestContext ctx) {
      return false;
    }
  }

}
