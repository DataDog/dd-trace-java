package datadog.trace.api.profiling;

public interface ContextTracker {
  ContextTracker EMPTY = new ContextTracker() {
    @Override
    public void activateContext() {}

    @Override
    public void deactivateContext(boolean maybe) {}

    @Override
    public void persist() {}
  };

  void activateContext();
  void deactivateContext(boolean maybe);
  void persist();
}
