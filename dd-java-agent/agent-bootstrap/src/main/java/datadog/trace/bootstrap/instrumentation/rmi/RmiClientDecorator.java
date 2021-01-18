package datadog.trace.bootstrap.instrumentation.rmi;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public class RmiClientDecorator extends ClientDecorator {
  public static final CharSequence RMI_INVOKE = UTF8BytesString.createConstant("rmi.invoke");
  public static final CharSequence RMI_CLIENT = UTF8BytesString.createConstant("rmi-client");
  public static final RmiClientDecorator DECORATE = new RmiClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"rmi", "rmi-client"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  protected CharSequence component() {
    return RMI_CLIENT;
  }

  @Override
  protected String service() {
    return null;
  }
}
