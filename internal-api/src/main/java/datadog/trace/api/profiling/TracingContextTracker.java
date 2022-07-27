package datadog.trace.api.profiling;

import datadog.trace.api.function.ToIntFunction;
import java.nio.ByteBuffer;

/** A tracing context tracker */
public interface TracingContextTracker {
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
   * Notify of the eventual context deactivation. The deactivation is conditional - if it is
   * followed by an activation the deactivation should be disregarded
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
}
