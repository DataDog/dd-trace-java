package datadog.trace.api.profiling;

import datadog.trace.api.function.ToIntFunction;
import java.nio.ByteBuffer;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/** A tracing context tracker */
public interface TracingContextTracker {
  /** Provides support for a timed tracker cleanup */
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

  /** A no-op implementation */
  TracingContextTracker EMPTY =
      new TracingContextTracker() {
        @Override
        public boolean release() {
          return false;
        }

        @Override
        public void activateContext() {}

        @Override
        public void deactivateContext() {}

        @Override
        public void maybeDeactivateContext() {}

        @Override
        public byte[] persist() {
          return null;
        }

        @Override
        public int persist(ToIntFunction<ByteBuffer> dataConsumer) {
          return 0;
        }

        @Override
        public int getVersion() {
          return 0;
        }

        @Override
        public DelayedTracker asDelayed() {
          return DelayedTracker.EMPTY;
        }

        @Override
        public String toString() {
          return "Empty context tracker";
        }
      };

  /**
   * Release any resources held by the tracker
   *
   * @return {@literal false} if already released; {@literal true} otherwise
   */
  boolean release();

  /** Notify of the context activation */
  void activateContext();

  /** Notify of the context deactivation */
  void deactivateContext();

  /**
   * Notify of the eventual context deactivation
   *
   * @param the deactivation is conditional - if it is followed by an activation the deactivation
   *     should be disregarded
   */
  void maybeDeactivateContext();

  /**
   * Convert the sparse 'on-line' representation into a compressed binary blob
   *
   * @return the binary blob of the context tracking data or {@literal null}
   */
  byte[] persist();

  int persist(ToIntFunction<ByteBuffer> dataConsumer);

  /**
   * The tracker version - should be in sync with the binary blob format
   *
   * @return the tracker version
   */
  int getVersion();

  /**
   * Turn this instance into a {@linkplain Delayed} one, suitable to be used in {@linkplain
   * java.util.concurrent.DelayQueue}
   *
   * @return the delayed version of this instance
   */
  DelayedTracker asDelayed();
}
