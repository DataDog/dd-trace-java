package datadog.trace.instrumentation.rmi.client;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public class RmiClientDecorator extends ClientDecorator {
  public static final RmiClientDecorator DECORATE = new RmiClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"rmi", "rmi-client"};
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.RPC;
  }

  @Override
  protected String component() {
    return "rmi-client";
  }

  @Override
  protected String service() {
    return null;
  }
}
