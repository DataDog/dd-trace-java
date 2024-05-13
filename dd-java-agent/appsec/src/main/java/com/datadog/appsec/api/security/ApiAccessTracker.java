package com.datadog.appsec.api.security;

import java.util.LinkedHashMap;

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
  private final LinkedHashMap<Long, Long> apiAccessLog; // Map<hash, timestamp>
  private final int capacity;
  private final long expirationTimeInMs;

  public ApiAccessTracker() {
    this(MAX_SIZE, INTERVAL_SECONDS * 1000);
  }

  public ApiAccessTracker(int capacity, long expirationTimeInMs) {
    this.capacity = capacity;
    this.expirationTimeInMs = expirationTimeInMs;
    this.apiAccessLog = new LinkedHashMap<>();
  }

  /**
   * Updates the API access log with the given route, method, and status code. If the record exists
   * and is outdated, it is updated by moving to the end of the list. If the record does not exist,
   * a new record is added. If the capacity limit is reached, the oldest record is removed. Returns
   * true if the record was updated or added, false otherwise.
   *
   * @param route
   * @param method
   * @param statusCode
   * @return return true if the record was updated or added, false otherwise
   */
  public boolean updateApiAccessIfExpired(String route, String method, int statusCode) {
    long currentTime = System.currentTimeMillis();
    long hash = computeApiHash(route, method, statusCode);

    // If the record exists and is outdated, update it by moving to the end of the list
    if (apiAccessLog.containsKey(hash)) {
      long lastAccessTime = apiAccessLog.get(hash);
      if (currentTime - lastAccessTime > expirationTimeInMs) {
        // Remove and add the record to update the timestamp and move it to the end of the list
        apiAccessLog.remove(hash);
        apiAccessLog.put(hash, currentTime);
        return true;
      }
      return false;
    } else {
      // If the record does not exist, just add a new one
      if (apiAccessLog.size() >= capacity) {
        // Remove the oldest record if the capacity limit is reached
        apiAccessLog.remove(apiAccessLog.keySet().iterator().next());
      }
      apiAccessLog.put(hash, currentTime);
      return true;
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
