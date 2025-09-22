package datadog.trace.instrumentation.axis2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_ASYNC_SPAN_KEY;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_TRANSPORT;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.axis2.context.MessageContext;

@AutoService(InstrumenterModule.class)
public final class WebSphereAsyncInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public WebSphereAsyncInstrumentation() {
    super("axis2", "axis2-transport");
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.ws.websvcs.transport.http.SOAPOverHTTPSender";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".AxisMessageDecorator"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("sendSOAPRequestAsync")),
        getClass().getName() + "$CaptureAsyncAdvice");
    transformer.applyAdvice(
        isMethod().and(named("releaseBuffer")), getClass().getName() + "$ReleaseAsyncAdvice");
  }

  public static final class CaptureAsyncAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beginAsync(@Advice.Argument(0) final MessageContext message) {
      AgentSpan span = activeSpan();
      if (null != span && AXIS2_TRANSPORT.equals(span.getSpanName())) {
        message.setNonReplicableProperty(AXIS2_ASYNC_SPAN_KEY, span);
      }
    }
  }

  public static final class ReleaseAsyncAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void finishAsync(@Advice.FieldValue("msgContext") final MessageContext message) {
      AgentSpan span = (AgentSpan) message.getPropertyNonReplicable(AXIS2_ASYNC_SPAN_KEY);
      if (null != span) {
        message.removePropertyNonReplicable(AXIS2_ASYNC_SPAN_KEY);
        // same as AxisTransportInstrumentation.TransportAdvice.finishTransport
        Object statusCode = message.getProperty("transport.http.statusCode");
        if (statusCode instanceof Integer) {
          span.setHttpStatusCode((Integer) statusCode);
        }
        DECORATE.beforeFinish(span, message);
        span.finish();
      }
    }
  }
}
