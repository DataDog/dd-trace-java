package com.datadog.appsec.event;

public enum EventType {
  REQUEST_START,
  REQUEST_END;

  static int NUM_EVENT_TYPES = EventType.values().length;
}
