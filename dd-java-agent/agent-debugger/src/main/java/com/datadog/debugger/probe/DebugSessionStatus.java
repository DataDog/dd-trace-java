package com.datadog.debugger.probe;

public enum DebugSessionStatus {
  NONE {
    @Override
    public boolean isDefined() {
      return false;
    }
  },
  ACTIVE,
  DISABLED;

  public boolean isDefined() {
    return true;
  }

  public boolean isDisabled() {
    return this == DISABLED;
  }

  public boolean isActive() {
    return this == ACTIVE;
  }
}
