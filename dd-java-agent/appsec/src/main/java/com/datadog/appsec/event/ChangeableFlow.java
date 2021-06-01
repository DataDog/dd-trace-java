package com.datadog.appsec.event;

import datadog.trace.api.gateway.Flow;

public class ChangeableFlow implements Flow<Void> {
  Action action = Action.Noop.INSTANCE;

  public boolean isBlocking() {
    return action.isBlocking();
  }

  public void setAction(Action blockingAction) {
    this.action = blockingAction;
  }

  @Override
  public Action getAction() {
    return action;
  }

  @Override
  public Void getResult() {
    return null;
  }
}
