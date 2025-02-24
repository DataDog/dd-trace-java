package com.datadog.appsec.api.security;

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

}
