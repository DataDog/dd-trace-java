package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.axis2.context.MessageContext;

/** Helps propagate parent spans over 'passthru' mechanism to synapse-client instrumentation. */
@AutoService(InstrumenterModule.class)
public final class SynapsePassthruInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SynapsePassthruInstrumentation() {
    super("synapse3-client", "synapse3");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.synapse.transport.passthru.DeliveryAgent";
  }

  @Override
  public void methodAdvice(final MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("submit"))
            .and(takesArgument(0, named("org.apache.axis2.context.MessageContext"))),
        getClass().getName() + "$PassthruAdvice");
  }

  public static final class PassthruAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void submit(@Advice.Argument(0) final MessageContext message) {

      // avoid leaking x-datadog headers from incoming server messages into client requests
      Object headers = message.getProperty(MessageContext.TRANSPORT_HEADERS);
      if (headers instanceof Map) {
        Iterator<Map.Entry<String, ?>> itr = ((Map) headers).entrySet().iterator();
        while (itr.hasNext()) {
          if (itr.next().getKey().toLowerCase(Locale.ROOT).startsWith("x-datadog")) {
            itr.remove();
          }
        }
      }

      // use message context to propagate active spans across Synapse's 'passthru' mechanism
      AgentSpan span = activeSpan();
      if (null != span) {
        message.setNonReplicableProperty("dd.trace.synapse.span", span);
      }
    }
  }
}
