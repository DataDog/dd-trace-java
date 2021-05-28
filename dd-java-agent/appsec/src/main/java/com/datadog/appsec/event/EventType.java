package com.datadog.appsec.event;

public enum EventType {
  REQUEST_START(0),
  REQUEST_END(1);

  public final int serial;

  EventType(int serial) {
    this.serial = serial;
  }
}
