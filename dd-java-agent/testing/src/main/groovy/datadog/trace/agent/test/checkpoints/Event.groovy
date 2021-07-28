package datadog.trace.agent.test.checkpoints

import static datadog.trace.api.Checkpointer.*
import datadog.trace.api.DDId

class Event {
  private final int flags
  private final long threadId
  private final String threadName
  private final DDId traceId
  private final DDId spanId

  Event(int flags, DDId traceId, DDId spanId, Thread thread) {
    this.flags = flags
    this.traceId = traceId
    this.spanId = spanId
    this.threadId = thread.id
    this.threadName = thread.name
  }

  String getName() {
    switch (flags) {
      case SPAN:
        return "startSpan"
      case SPAN | END:
        return "endSpan"
      case THREAD_MIGRATION:
        return "suspend"
      case THREAD_MIGRATION | END:
        return "resume"
      case CPU | END:
        return "endTask"
      case CPU:
        return "startTask"
      default:
        return "unknown"
    }
  }

  int getFlags() {
    return flags
  }

  DDId getTraceId() {
    return traceId
  }

  DDId getSpanId() {
    return spanId
  }

  long getThreadId() {
    return threadId
  }

  String getThreadName() {
    return threadName
  }

  StackTraceElement[] getStackTrace() {
    return stackTrace
  }

  String toString() {
    return "${name}/${spanId} (thread: ${threadName})\n"
  }
}
