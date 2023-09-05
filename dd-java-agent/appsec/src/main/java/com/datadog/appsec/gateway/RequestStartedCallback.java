package com.datadog.appsec.gateway;

import com.datadog.appsec.AppSecSystem;
import datadog.trace.api.gateway.Flow;
import java.util.function.Supplier;

class RequestStartedCallback implements Supplier<Flow<AppSecRequestContext>> {

  @Override
  public Flow<AppSecRequestContext> get() {
    if (!AppSecSystem.isActive()) {
      return RequestContextSupplier.EMPTY;
    }
    return new RequestContextSupplier();
  }

  private static class RequestContextSupplier implements Flow<AppSecRequestContext> {
    private static final Flow<AppSecRequestContext> EMPTY = new RequestContextSupplier(null);

    private final AppSecRequestContext appSecRequestContext;

    public RequestContextSupplier() {
      this(new AppSecRequestContext());
    }

    public RequestContextSupplier(AppSecRequestContext ctx) {
      appSecRequestContext = ctx;
    }

    @Override
    public Action getAction() {
      return Action.Noop.INSTANCE;
    }

    @Override
    public AppSecRequestContext getResult() {
      return appSecRequestContext;
    }
  }
}
