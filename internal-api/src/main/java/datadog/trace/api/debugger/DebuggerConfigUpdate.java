package datadog.trace.api.debugger;

import java.util.Objects;

public class DebuggerConfigUpdate {
  private final Boolean dynamicInstrumentationEnabled;
  private final Boolean exceptionReplayEnabled;
  private final Boolean codeOriginEnabled;
  private final Boolean liveDebuggingEnabled;

  private DebuggerConfigUpdate(Builder builder) {
    this.dynamicInstrumentationEnabled = builder.dynamicInstrumentationEnabled;
    this.exceptionReplayEnabled = builder.exceptionReplayEnabled;
    this.codeOriginEnabled = builder.codeOriginEnabled;
    this.liveDebuggingEnabled = builder.liveDebuggingEnabled;
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

  public Boolean getLiveDebuggingEnabled() {
    return liveDebuggingEnabled;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof DebuggerConfigUpdate)) return false;
    DebuggerConfigUpdate that = (DebuggerConfigUpdate) o;
    return Objects.equals(dynamicInstrumentationEnabled, that.dynamicInstrumentationEnabled)
        && Objects.equals(exceptionReplayEnabled, that.exceptionReplayEnabled)
        && Objects.equals(codeOriginEnabled, that.codeOriginEnabled)
        && Objects.equals(liveDebuggingEnabled, that.liveDebuggingEnabled);
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
        + ", liveDebuggingEnabled="
        + liveDebuggingEnabled
        + '}';
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        dynamicInstrumentationEnabled,
        exceptionReplayEnabled,
        codeOriginEnabled,
        liveDebuggingEnabled);
  }

  public boolean hasUpdates() {
    return dynamicInstrumentationEnabled != null
        || exceptionReplayEnabled != null
        || codeOriginEnabled != null
        || liveDebuggingEnabled != null;
  }

  public static final class Builder {
    private Boolean dynamicInstrumentationEnabled;
    private Boolean exceptionReplayEnabled;
    private Boolean codeOriginEnabled;
    private Boolean liveDebuggingEnabled;

    public Builder setDynamicInstrumentationEnabled(Boolean dynamicInstrumentationEnabled) {
      this.dynamicInstrumentationEnabled = dynamicInstrumentationEnabled;
      return this;
    }

    public Builder setExceptionReplayEnabled(Boolean exceptionReplayEnabled) {
      this.exceptionReplayEnabled = exceptionReplayEnabled;
      return this;
    }

    public Builder setCodeOriginEnabled(Boolean codeOriginEnabled) {
      this.codeOriginEnabled = codeOriginEnabled;
      return this;
    }

    public Builder setLiveDebuggingEnabled(Boolean liveDebuggingEnabled) {
      this.liveDebuggingEnabled = liveDebuggingEnabled;
      return this;
    }

    public DebuggerConfigUpdate build() {
      return new DebuggerConfigUpdate(this);
    }
  }
}
