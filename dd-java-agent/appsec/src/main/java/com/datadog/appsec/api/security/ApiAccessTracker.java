package com.datadog.appsec.api.security;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The ApiAccessTracker class provides a mechanism to track API access events, managing them within
 * a specified capacity limit. Each event is associated with a unique combination of route, method,
 * and status code, which is used to generate a unique key for tracking access timestamps.
 *
 * <p>Usage: - When an API access event occurs, the `updateApiAccessIfExpired` method is called with
 * the route, method, and status code of the API request. - If the access event for the given
 * parameters is new or has expired (based on the expirationTimeInMs threshold), the event's
 * timestamp is updated, effectively moving the event to the end of the tracking list. - If the
 * tracker's capacity is reached, the oldest event is automatically removed to make room for new
 * events. - This mechanism ensures that the tracker always contains the most recent access events
 * within the specified capacity limit, with older, less relevant events being discarded.
 */
public class ApiAccessTracker {
  private static final int INTERVAL_SECONDS = 30;
  private static final int MAX_SIZE = 4096;
  private final Map<Long, Long> apiAccessMap; // Map<hash, timestamp>
  private final Deque<Long> apiAccessQueue; // hashes ordered by access time
  private final long expirationTimeInMs;
  private final int capacity;

  public ApiAccessTracker() {
    this(MAX_SIZE, INTERVAL_SECONDS * 1000);
  }

  public ApiAccessTracker(int capacity, long expirationTimeInMs) {
    this.capacity = capacity;
    this.expirationTimeInMs = expirationTimeInMs;
    this.apiAccessMap = new ConcurrentHashMap<>();
    this.apiAccessQueue = new ConcurrentLinkedDeque<>();
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
}
