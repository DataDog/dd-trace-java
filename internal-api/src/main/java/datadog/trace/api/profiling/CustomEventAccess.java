package datadog.trace.api.profiling;

public interface CustomEventAccess {
  CustomEventAccess NULL =
      new CustomEventAccess() {
        @Override
        public void emitTraceContextEvent(
            long localRootSpanId, long threadId, long startTime, long duration) {}
      };

  void emitTraceContextEvent(long localRootSpanId, long threadId, long startTime, long duration);
}
