package org.apache.spark.sql.connect.service

import org.apache.spark.scheduler.SparkListenerEvent

/**
 * Test stand-in for the Spark Connect server event of the same name (Spark 3.5+), which is not on
 * the test classpath. The listener matches it by class name and reads these accessors.
 */
class SparkListenerConnectSessionStarted implements SparkListenerEvent {
  private final String sessionId
  private final String userId
  private final long eventTime

  SparkListenerConnectSessionStarted(String sessionId, String userId, long eventTime) {
    this.sessionId = sessionId
    this.userId = userId
    this.eventTime = eventTime
  }

  String sessionId() {
    sessionId
  }

  String userId() {
    userId
  }

  long eventTime() {
    eventTime
  }
}
