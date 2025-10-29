package datadog.trace.common.writer.ddagent;

import datadog.trace.common.writer.Payload;

/** Represents a payload queued for retry with backoff metadata */
class RetryEntry {
  final Payload payload;
  final long retryAfterMs;
  final int attemptCount;

  RetryEntry(Payload payload, long retryAfterMs, int attemptCount) {
    this.payload = payload;
    this.retryAfterMs = retryAfterMs;
    this.attemptCount = attemptCount;
  }
}
