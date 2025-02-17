package datadog.trace.instrumentation.axis2;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_ASYNC_SPAN_KEY;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.AXIS2_TRANSPORT;
import static datadog.trace.instrumentation.axis2.AxisMessageDecorator.DECORATE;
import static datadog.trace.instrumentation.axis2.TextMapInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.axis2.context.MessageContext;

@AutoService(InstrumenterModule.class)
public final class AxisTransportInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes,
        Instrumenter.ForConfiguredType,
        Instrumenter.HasMethodAdvice {

  public AxisTransportInstrumentation() {
    super("axis2", "axis2-transport");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"com.ibm.ws.websvcs.transport.http.HTTPTransportSender"};
  }

  @Override
  public String configuredMatchingType() {
    // this won't match any class unless the property is set
    return InstrumenterConfig.get().getAxisTransportClassName();
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AxisMessageDecorator", packageName + ".TextMapInjectAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("invoke"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$TransportAdvice");
  }

  public static final class TransportAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginTransport(@Advice.Argument(0) final MessageContext message) {
      // only create a span if the message has a clear action and there's a surrounding request
      if (DECORATE.shouldTrace(message)) {
        AgentSpan span = startSpan(AXIS2_TRANSPORT);
        DECORATE.afterStart(span);
        DECORATE.onTransport(span, message);
        DECORATE.onMessage(span, message);

        // the transport handler will copy TRANSPORT_HEADERS to the outgoing request
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> headers = (Map) message.getProperty("TRANSPORT_HEADERS");
        if (null == headers) {
          headers = new HashMap<>();
          message.setProperty("TRANSPORT_HEADERS", headers);
        }
        try {
          defaultPropagator().inject(span, headers, SETTER);
        } catch (Throwable ignore) {
        }

        return activateSpan(span);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishTransport(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final MessageContext message,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }

      AgentSpan span = scope.span();
      if (null != error) {
        // cancel async tracking when we know there's an error
        message.removePropertyNonReplicable(AXIS2_ASYNC_SPAN_KEY);
        DECORATE.onError(span, error);
      }
      scope.close();
      if (span.equals(message.getPropertyNonReplicable(AXIS2_ASYNC_SPAN_KEY))) {
        // delay finishing span until async callback completes...
      } else {
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
