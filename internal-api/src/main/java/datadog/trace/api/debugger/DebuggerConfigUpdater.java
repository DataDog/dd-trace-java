package datadog.trace.api.debugger;

public interface DebuggerConfigUpdater {
  void updateConfig(DebuggerConfigUpdate update);

  boolean isDynamicInstrumentationEnabled();

  boolean isExceptionReplayEnabled();

  boolean isCodeOriginEnabled();

  boolean isDistributedDebuggerEnabled();
}
