package datadog.trace.api.profiling;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.nio.ByteBuffer;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public interface TracingContextTracker {
  interface DelayedTracker extends Delayed {
    DelayedTracker EMPTY =
        new DelayedTracker() {
          @Override
          public void cleanup() {}

          @Override
          public long getDelay(TimeUnit unit) {
            return -1;
          }

          @Override
          public int compareTo(Delayed o) {
            return -1;
          }
        };

    void cleanup();
  }

  interface IntervalBlobListener {
    void onIntervalBlob(AgentSpan span, ByteBuffer blob);
  }

  TracingContextTracker EMPTY =
      new TracingContextTracker() {
        @Override
        public boolean release() {
          return false;
        }

        @Override
        public void activateContext() {}

        @Override
        public void deactivateContext(boolean maybe) {}

        @Override
        public byte[] persist() {
          return null;
        }

        @Override
        public int getVersion() {
          return 0;
        }

        @Override
        public DelayedTracker asDelayed() {
          return DelayedTracker.EMPTY;
        }
      };

  boolean release();

  void activateContext();

  void deactivateContext(boolean maybe);

  byte[] persist();

  int getVersion();

  DelayedTracker asDelayed();
}
