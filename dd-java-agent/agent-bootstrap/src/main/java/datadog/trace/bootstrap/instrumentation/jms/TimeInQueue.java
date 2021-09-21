package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** Holds the synthetic time-in-queue span for a batch of messages. */
final class TimeInQueue {
  final long batchId;
  final AgentSpan span;

  TimeInQueue(long batchId, AgentSpan span) {
    this.batchId = batchId;
    this.span = span;
  }
}
