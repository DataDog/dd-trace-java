package com.datadog.appsec.gateway;

import com.datadog.appsec.AppSecSystem;
import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventType;
import datadog.trace.api.gateway.Flow;
import java.util.function.Supplier;

class RequestStartedCallback implements Supplier<Flow<AppSecRequestContext>> {

  private final EventProducerService producerService;

  public RequestStartedCallback(final EventProducerService producerService) {
    this.producerService = producerService;
  }

  @Override
  public Flow<AppSecRequestContext> get() {
    if (!AppSecSystem.isActive()) {
      return RequestContextSupplier.EMPTY;
    }

    RequestContextSupplier requestContextSupplier = new RequestContextSupplier();
    AppSecRequestContext ctx = requestContextSupplier.getResult();
    producerService.publishEvent(ctx, EventType.REQUEST_START);

    return requestContextSupplier;
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
