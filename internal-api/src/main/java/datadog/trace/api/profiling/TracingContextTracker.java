package datadog.trace.api.profiling;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;

public interface TracingContextTracker {
  interface IntervalBlobListener {
    void onIntervalBlob(AgentSpan span, ByteBuffer blob);
  }

  TracingContextTracker EMPTY =
      new TracingContextTracker() {
        @Override
        public void activateContext() {}

        @Override
        public void deactivateContext(boolean maybe) {}

        @Override
        public byte[] persist() {
          return null;
        }

        @Override
        public byte[] persistAndRelease() {
          return null;
        }

        @Override
        public void release() {}

        @Override
        public int getVersion() {
          return 0;
        }
      };

  void activateContext();

  void deactivateContext(boolean maybe);

  byte[] persist();

  byte[] persistAndRelease();

  void release();

  int getVersion();
}
