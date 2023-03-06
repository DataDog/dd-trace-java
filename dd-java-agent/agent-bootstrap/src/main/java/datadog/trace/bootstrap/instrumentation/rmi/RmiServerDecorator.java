package datadog.trace.bootstrap.instrumentation.rmi;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ServerDecorator;

public class RmiServerDecorator extends ServerDecorator {
  public static final CharSequence RMI_SERVER = UTF8BytesString.create("rmi-server");
  public static final RmiServerDecorator DECORATE = new RmiServerDecorator();
  public static final CharSequence RMI_REQUEST =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().server().operationForProtocol("rmi"));

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"rmi", "rmi-server"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  protected CharSequence component() {
    return RMI_SERVER;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setMeasured(true);
    return super.afterStart(span);
  }
}
