package datadog.trace.instrumentation.axis1;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.axis.MessageContext;
import org.apache.axis.description.OperationDesc;

import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.SOAP;

public class AxisMessageDecorator extends BaseDecorator {
  public static final AxisMessageDecorator DECORATE = new AxisMessageDecorator();

  public static final CharSequence AXIS = UTF8BytesString.create("axis");
  public static final CharSequence AXIS2_MESSAGE = UTF8BytesString.create("axis.message");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"axis"};
  }

  @Override
  protected CharSequence spanType() {
    return SOAP;
  }

  @Override
  protected CharSequence component() {
    return AXIS;
  }

  public void onMessage(final AgentSpan span, final MessageContext message) {
    span.setResourceName(soapAction(message));
  }

  public void beforeFinish(final AgentSpan span, final MessageContext message) {
    super.beforeFinish(span);
  }

  public boolean sameTrace(final AgentSpan span, final MessageContext message) {
    return AXIS2_MESSAGE.equals(span.getSpanName())
        && span.getResourceName().equals(soapAction(message));
  }

  private static String soapAction(final MessageContext message) {
    OperationDesc operation = message.getOperation();
    if(null != operation){
           return operation.getName();
     }
    String action = message.getSOAPActionURI();
    if (null != action && !action.isEmpty()) {
      return action;
    }
    if (message.getTransportName() != null) {
      return message.getTransportName();
    }
    return null;
  }
}
