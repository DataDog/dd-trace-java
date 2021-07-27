package datadog.trace.agent.test.checkpoints

class TimelineExporter {
  static void export(def orderedEvents) {
    if (Boolean.parseBoolean(System.getenv("TIMELINECHECKPOINTER_PRINT_PROFILING_TESTCASE"))) {
      PrintStream out = null
      try {
        out = new PrintStream(File.createTempFile("checkpointer-events", ".csv", null))
        out.println("eventFlags,traceId,spanId,threadId")
        for (Event event : orderedEvents) {
          out.println(String.format("%d,%s,%s,%d", event.flags, event.traceId.toHexString(), event.spanId.toHexString(), event.threadId))
        }
        out.flush()
      } finally {
        if (out != null) {
          out.close()
        }
      }
    }
  }
}
