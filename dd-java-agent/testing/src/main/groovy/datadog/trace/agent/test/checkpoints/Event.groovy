package datadog.trace.agent.test.checkpoints

import datadog.trace.api.DDTraceId

class Event {
  private static final boolean DUMP_STACKTRACES = Boolean.parseBoolean(System.getProperty("dd.checkpoints.dump.stacktraces", "false"))
  private final boolean begin
  private final DDTraceId traceId
  private final long spanId
  private final Thread thread
  private final StackTraceElement[] stackTrace

  Event(boolean begin, DDTraceId traceId, long spanId, Thread thread) {
    this.begin = begin
    this.traceId = traceId
    this.spanId = spanId
    this.thread = thread

    if (DUMP_STACKTRACES) {
      def idx = -1
      def strace = thread.stackTrace
      for (int i = 0; i < strace.length; i++) {
        def frame = strace[i]
        if (frame.className == "datadog.trace.agent.test.checkpoints.TimelineTracingContextTracker") {
          // the interesting information is in the next frame
          idx = i + 1
          break
        }
      }
      if (idx > -1) {
        stackTrace = Arrays.copyOfRange(strace, idx, strace.length - 1)
      } else {
        stackTrace = new StackTraceElement[0]
      }
    } else {
      stackTrace = null
    }
  }

  String getName() {
    return begin ? "startTask" : "endTask"
  }

  DDTraceId getTraceId() {
    return traceId
  }

  long getSpanId() {
    return spanId
  }

  Thread getThread() {
    return thread
  }

  long getThreadId() {
    return thread.id
  }

  String getThreadName() {
    return thread.name
  }

  StackTraceElement[] getStackTrace() {
    return stackTrace
  }

  String toString() {
    def str = "${name}/${spanId} (thread: ${threadName})\n"
    if (stackTrace != null) {
      str += stackTrace.grep {
        !(it.className.startsWith("org.codehaus.groovy") || it.className.startsWith("groovy")) &&
          !(it.className.startsWith("org.spockframework"))
      }.collect {
        "  " + it.toString()
      }
      .join("\n") +
      "\n"
    }
    return str
  }
}
