package datadog.trace.api.profiling;

public interface ProfilingContextTracker {
  ProfilingContextTracker EMPTY =
      new ProfilingContextTracker() {
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
