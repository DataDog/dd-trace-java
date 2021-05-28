package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.Flow;

public enum NoopFlow implements Flow<Void> {
  INSTANCE;

  @Override
  public Action getAction() {
    return Action.Noop.INSTANCE;
  }

  @Override
  public Void getResult() {
    return null;
  }
}
