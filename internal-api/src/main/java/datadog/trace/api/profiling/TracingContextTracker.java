package datadog.trace.api.profiling;

public interface TracingContextTracker {
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
      };

  void activateContext();

  void deactivateContext(boolean maybe);

  byte[] persist();

  byte[] persistAndRelease();

  void release();
}
