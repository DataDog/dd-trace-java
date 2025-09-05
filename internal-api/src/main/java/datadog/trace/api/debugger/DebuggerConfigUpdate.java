package datadog.trace.api.debugger;

public class DebuggerConfigUpdate {
  private final Boolean dynamicInstrumentationEnabled;
  private final Boolean exceptionReplayEnabled;
  private final Boolean codeOriginEnabled;
  private final Boolean distributedDebuggerEnabled;

  public DebuggerConfigUpdate() {
    this(null, null, null, null);
  }

  public DebuggerConfigUpdate(
      Boolean dynamicInstrumentationEnabled,
      Boolean exceptionReplayEnabled,
      Boolean codeOriginEnabled,
      Boolean distributedDebuggerEnabled) {
    this.dynamicInstrumentationEnabled = dynamicInstrumentationEnabled;
    this.exceptionReplayEnabled = exceptionReplayEnabled;
    this.codeOriginEnabled = codeOriginEnabled;
    this.distributedDebuggerEnabled = distributedDebuggerEnabled;
  }

  public Boolean getDynamicInstrumentationEnabled() {
    return dynamicInstrumentationEnabled;
  }

  public Boolean getExceptionReplayEnabled() {
    return exceptionReplayEnabled;
  }

  public Boolean getCodeOriginEnabled() {
    return codeOriginEnabled;
  }

  public Boolean getDistributedDebuggerEnabled() {
    return distributedDebuggerEnabled;
  }

  @Override
  public String toString() {
    return "DebuggerConfigUpdate{"
        + "dynamicInstrumentationEnabled="
        + dynamicInstrumentationEnabled
        + ", exceptionReplayEnabled="
        + exceptionReplayEnabled
        + ", codeOriginEnabled="
        + codeOriginEnabled
        + ", distributedDebuggerEnabled="
        + distributedDebuggerEnabled
        + '}';
  }

  public boolean hasUpdates() {
    return dynamicInstrumentationEnabled != null
        || exceptionReplayEnabled != null
        || codeOriginEnabled != null
        || distributedDebuggerEnabled != null;
  }

  public static DebuggerConfigUpdate coalesce(
      DebuggerConfigUpdate existing, DebuggerConfigUpdate update) {
    if (existing == null) {
      return update;
    }

    return new DebuggerConfigUpdate(
        coalesceSetting(
            existing.dynamicInstrumentationEnabled, update.dynamicInstrumentationEnabled),
        coalesceSetting(existing.exceptionReplayEnabled, update.exceptionReplayEnabled),
        coalesceSetting(existing.codeOriginEnabled, update.codeOriginEnabled),
        coalesceSetting(existing.distributedDebuggerEnabled, update.distributedDebuggerEnabled));
  }

  private static Boolean coalesceSetting(Boolean existing, Boolean update) {
    return update != null ? update : existing;
  }
}
