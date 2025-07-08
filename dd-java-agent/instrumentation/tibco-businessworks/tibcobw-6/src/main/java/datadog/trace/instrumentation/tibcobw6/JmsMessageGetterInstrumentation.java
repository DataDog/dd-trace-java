package datadog.trace.instrumentation.tibcobw6;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.tibco.bw.jms.shared.api.receive.JMSMessageCallBackHandler;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class JmsMessageGetterInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JmsMessageGetterInstrumentation() {
    super("jms");
  }

  @Override
  public String instrumentedType() {
    return "com.tibco.bw.jms.shared.primitives.SingleJMSMessageGetter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.FieldValue(value = "callBackHandler") final JMSMessageCallBackHandler handler) {
      // If we inferred the service name we should capture it and set on the receiver side as well
      // (otherwise it will be missing or different)
      if (handler != null && !Config.get().isServiceNameSetByUser()) {
        final AgentSpan span = activeSpan();
        if (span != null && span.getLocalRootSpan() != null) {
          final String pinnedName = span.getLocalRootSpan().getServiceName();
          InstrumentationContext.get(JMSMessageCallBackHandler.class, String.class)
              .put(handler, pinnedName);
        }
      }
    }
  }
}
