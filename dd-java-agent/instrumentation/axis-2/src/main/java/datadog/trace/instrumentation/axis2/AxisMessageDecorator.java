package datadog.trace.instrumentation.axis2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.SOAP;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.axis2.context.MessageContext;

public class AxisMessageDecorator extends BaseDecorator {
  public static final AxisMessageDecorator DECORATE = new AxisMessageDecorator();

  public static final CharSequence AXIS2 = UTF8BytesString.create("axis2");
  public static final CharSequence AXIS2_MESSAGE = UTF8BytesString.create("axis2.message");
  public static final String AXIS2_CONTINUATION_KEY = "dd.trace.axis2.continuation";

  private AxisMessageDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"axis2"};
  }

  @Override
  protected CharSequence spanType() {
    return SOAP;
  }

  @Override
  protected CharSequence component() {
    return AXIS2;
  }

  public boolean shouldTrace(final MessageContext message) {
    if (message.isServerSide() && null == message.getOperationContext()) {
      return false; // ignore server messages without an associated operation
    }
    return null != activeSpan() && null != soapAction(message);
  }

  public boolean sameTrace(final AgentSpan span, final MessageContext message) {
    return AXIS2_MESSAGE.equals(span.getSpanName())
        && span.getResourceName().toString().equals(soapAction(message));
  }

  public void onMessage(final AgentSpan span, final MessageContext message) {
    final CharSequence soapAction = UTF8BytesString.create(soapAction(message));
    span.setResourceName(soapAction);
    if (message.isServerSide() && Config.get().isAxisPromoteResourceName()) {
      final AgentSpan localRoot = span.getLocalRootSpan();
      // explicitly check for strict comparison in order to set only once (if multiple axis
      // messages)
      if (localRoot != null
          && localRoot.getResourceNamePriority() < ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE) {
        localRoot.setResourceName(
            soapAction,
            ResourceNamePriorities
                .HTTP_FRAMEWORK_ROUTE); // reusing this since functionally equivalent
      }
    }
  }

  public void beforeFinish(final AgentSpan span, final MessageContext message) {
    if (message.isProcessingFault()) {
      span.setError(true);
    }
    super.beforeFinish(span);
  }

  private static String soapAction(final MessageContext message) {
    String action = message.getSoapAction();
    if (null != action && !action.isEmpty()) {
      return action;
    }
    if (message.getTo() != null) {
      return message.getTo().getAddress();
    }
    return null;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    return super.afterStart(span).setMeasured(true);
  }
}
