package com.datadog.appsec.gateway;

import datadog.trace.api.gateway.Flow;

public final class NoopFlow implements Flow<Void> {
  private NoopFlow() {}

  public static final NoopFlow INSTANCE = new NoopFlow();

  @Override
  public Action getAction() {
    return Action.Noop.INSTANCE;
  }

  @Override
  public Void getResult() {
    return null;
  }
}
