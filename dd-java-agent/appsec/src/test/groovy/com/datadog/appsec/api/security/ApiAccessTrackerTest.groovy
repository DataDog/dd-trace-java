package com.datadog.appsec.api.security

import datadog.trace.test.util.DDSpecification

class ApiAccessTrackerTest extends DDSpecification {
  def "should add new api access and update if expired"() {
    given: "An ApiAccessTracker with capacity 2 and expiration time 1 second"
    def tracker = new ApiAccessTracker(2, 1000)

    when: "Adding new api access"
    tracker.updateApiAccessIfExpired("route1", "GET", 200)
    def firstAccessTime = tracker.apiAccessLog.values().iterator().next()

    then: "The access is added"
    tracker.apiAccessLog.size() == 1

    when: "Waiting more than expiration time and adding another access with the same key"
    Thread.sleep(1100) // Waiting more than 1 second to ensure expiration
    tracker.updateApiAccessIfExpired("route1", "GET", 200)
    def secondAccessTime = tracker.apiAccessLog.values().iterator().next()

    then: "The access is updated and moved to the end"
    tracker.apiAccessLog.size() == 1
    secondAccessTime > firstAccessTime
  }

  def "should remove the oldest access when capacity is exceeded"() {
    given: "An ApiAccessTracker with capacity 1"
    def tracker = new ApiAccessTracker(1, 1000)

    when: "Adding two api accesses"
    tracker.updateApiAccessIfExpired("route1", "GET", 200)
    Thread.sleep(100) // Delay to ensure different timestamps
    tracker.updateApiAccessIfExpired("route2", "POST", 404)

    then: "The oldest access is removed"
    tracker.apiAccessLog.size() == 1
    !tracker.apiAccessLog.containsKey(tracker.computeApiHash("route1", "GET", 200))
    tracker.apiAccessLog.containsKey(tracker.computeApiHash("route2", "POST", 404))
  }

  def "should not update access if not expired"() {
    given: "An ApiAccessTracker with a short expiration time"
    def tracker = new ApiAccessTracker(2, 2000) // 2 seconds expiration

    when: "Adding an api access and updating it before it expires"
    tracker.updateApiAccessIfExpired("route1", "GET", 200)
    def updateTime = System.currentTimeMillis()
    boolean updatedBeforeExpiration = tracker.updateApiAccessIfExpired("route1", "GET", 200)

    then: "The access is not updated"
    !updatedBeforeExpiration
    tracker.apiAccessLog.get(tracker.computeApiHash("route1", "GET", 200)) == updateTime
  }
}