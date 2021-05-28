package com.datadog.appsec.event;

import datadog.trace.api.gateway.Flow;

public class ChangeableFlow implements Flow<Void> {
  Action blockingAction;

  public boolean shouldBlock() {
    return blockingAction != null;
  }

  public void setBlockingAction(Action blockingAction) {
    this.blockingAction = blockingAction;
  }

  public Action getBlockingAction() {
    return blockingAction;
  }

  @Override
  public Action getAction() {
    return null;
  }

  @Override
  public Void getResult() {
    return null;
  }
}
