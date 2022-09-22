package datadog.trace.agent.test.checkpoints

import static datadog.trace.api.Checkpointer.*
import datadog.trace.api.DDId

class Event {
  private static final boolean DUMP_STACKTRACES = Boolean.parseBoolean(System.getProperty("dd.checkpoints.dump.stacktraces", "false"))
  private final int flags
  private final DDId traceId
  private final DDId spanId
  private final Thread thread
  private final StackTraceElement[] stackTrace

  Event(int flags, DDId traceId, DDId spanId, Thread thread) {
    this.flags = flags
    this.traceId = traceId
    this.spanId = spanId
    this.thread = thread

    if (DUMP_STACKTRACES) {
      def idx = -1
      def strace = thread.stackTrace
      for (int i = 0; i < strace.length; i++) {
        def frame = strace[i]
        if (frame.className == "datadog.trace.api.SamplingCheckpointer" || frame.className == "datadog.trace.agent.test.checkpoints.TimelineTracingContextTracker") {
          // this is SamplingCheckpointer.checkpoint()
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
