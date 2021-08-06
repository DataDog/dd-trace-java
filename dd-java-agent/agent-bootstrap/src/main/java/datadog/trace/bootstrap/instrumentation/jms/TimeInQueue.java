package datadog.trace.bootstrap.instrumentation.jms;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

final class TimeInQueue {
  final long batchId;
  final AgentSpan span;

  TimeInQueue(long batchId, AgentSpan span) {
    this.batchId = batchId;
    this.span = span;
  }
}
