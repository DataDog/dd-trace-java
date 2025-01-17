package com.datadog.debugger.probe;

public enum DebugSessionStatus {
  NONE,
  ACTIVE,
  DISABLED;

  public boolean isDisabled() {
    return this == DISABLED;
  }
}
