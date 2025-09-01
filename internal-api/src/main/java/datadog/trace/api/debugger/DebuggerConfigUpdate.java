package datadog.trace.api.debugger;

import java.util.Objects;

public class DebuggerConfigUpdate {
  private final Boolean dynamicInstrumentationEnabled;
  private final Boolean exceptionReplayEnabled;
  private final Boolean codeOriginEnabled;
  private final Boolean distributedDebuggerEnabled;

  private DebuggerConfigUpdate(Builder builder) {
    this.dynamicInstrumentationEnabled = builder.dynamicInstrumentationEnabled;
    this.exceptionReplayEnabled = builder.exceptionReplayEnabled;
    this.codeOriginEnabled = builder.codeOriginEnabled;
    this.distributedDebuggerEnabled = builder.distributedDebuggerEnabled;
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
  public boolean equals(Object o) {
    if (!(o instanceof DebuggerConfigUpdate)) return false;
    DebuggerConfigUpdate that = (DebuggerConfigUpdate) o;
    return Objects.equals(dynamicInstrumentationEnabled, that.dynamicInstrumentationEnabled)
        && Objects.equals(exceptionReplayEnabled, that.exceptionReplayEnabled)
        && Objects.equals(codeOriginEnabled, that.codeOriginEnabled)
        && Objects.equals(distributedDebuggerEnabled, that.distributedDebuggerEnabled);
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

  @Override
  public int hashCode() {
    return Objects.hash(
        dynamicInstrumentationEnabled,
        exceptionReplayEnabled,
        codeOriginEnabled,
        distributedDebuggerEnabled);
  }

  public boolean hasUpdates() {
    return dynamicInstrumentationEnabled != null
        || exceptionReplayEnabled != null
        || codeOriginEnabled != null
        || distributedDebuggerEnabled != null;
  }

  public static final class Builder {
    private Boolean dynamicInstrumentationEnabled;
    private Boolean exceptionReplayEnabled;
    private Boolean codeOriginEnabled;
    private Boolean distributedDebuggerEnabled;

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

    public Builder setDistributedDebuggerEnabled(Boolean distributedDebuggerEnabled) {
      this.distributedDebuggerEnabled = distributedDebuggerEnabled;
      return this;
    }

    public Builder enableAll() {
      return setDynamicInstrumentationEnabled(true)
          .setExceptionReplayEnabled(true)
          .setCodeOriginEnabled(true)
          .setDistributedDebuggerEnabled(true);
    }

    public Builder disableAll() {
      return setDynamicInstrumentationEnabled(false)
          .setExceptionReplayEnabled(false)
          .setCodeOriginEnabled(false)
          .setDistributedDebuggerEnabled(false);
    }

    public DebuggerConfigUpdate build() {
      return new DebuggerConfigUpdate(this);
    }
  }

  public static DebuggerConfigUpdate empty() {
    return new Builder().build();
  }

  public static DebuggerConfigUpdate allEnabled() {
    return new Builder().enableAll().build();
  }

  public static DebuggerConfigUpdate allDisabled() {
    return new Builder().disableAll().build();
  }
}
