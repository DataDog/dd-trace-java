package com.datadog.appsec.gateway.callbacks;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.Flow;
import java.util.function.Supplier;

public class RequestStartedCallback implements Supplier<Flow<AppSecRequestContext>> {

  @Override
  public Flow<AppSecRequestContext> get() {
    if (!AppSecSystem.isActive()) {
      return RequestContextSupplier.EMPTY;
    }
    return new RequestContextSupplier();
  }

  public static class RequestContextSupplier implements Flow<AppSecRequestContext> {

    public static final Flow<AppSecRequestContext> EMPTY = new RequestContextSupplier(null);

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
