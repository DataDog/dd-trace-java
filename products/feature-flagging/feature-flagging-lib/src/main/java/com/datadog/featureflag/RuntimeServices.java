package com.datadog.featureflag;

/** Assembly-owned thread creation and diagnostics for shared event pipelines. */
public interface RuntimeServices {
  Thread newThread(String role, Runnable task);

  void countMetric(String name, long value, String reason);

  RuntimeServices STANDALONE =
      new RuntimeServices() {
        @Override
        public Thread newThread(String role, Runnable task) {
          Thread thread = new Thread(task, "dd-feature-flags-" + role);
          thread.setDaemon(true);
          thread.setContextClassLoader(null);
          return thread;
        }

        @Override
        public void countMetric(String name, long value, String reason) {
          // Standalone has no agent telemetry collector. Event delivery does not require one.
        }
      };
}
