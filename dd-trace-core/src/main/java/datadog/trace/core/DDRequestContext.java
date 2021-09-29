package datadog.trace.core;

import datadog.trace.api.gateway.RequestContext;

/** Holder for data that is relevant to a user of the {@code InstrumentationGateway}. */
public class DDRequestContext implements RequestContext<Object> {

  public static DDRequestContext create(Object data) {
    if (null == data) {
      return null;
    } else {
      return new DDRequestContext(data);
    }
  }

  private final Object data;

  public DDRequestContext(Object data) {
    this.data = data;
  }

  @Override
  public Object getData() {
    return data;
  }
}
