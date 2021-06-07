package com.datadog.appsec.event;

import com.datadog.appsec.gateway.AppSecRequestContext;

public interface EventListener extends OrderedCallback {
  void onEvent(AppSecRequestContext ctx, EventType eventType);
}
