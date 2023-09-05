package com.datadog.appsec.event;

public enum EventType {
  REQUEST_START,
  REQUEST_END,

  // modules can subscribe to these two to implement heuristics
  // dependent on whether the body is on the process of being read.
  // After REQUEST_BODY_START, AppSecRequestContext::getStoredRequestBody() is avail
  REQUEST_BODY_START,
  REQUEST_BODY_END;
}
