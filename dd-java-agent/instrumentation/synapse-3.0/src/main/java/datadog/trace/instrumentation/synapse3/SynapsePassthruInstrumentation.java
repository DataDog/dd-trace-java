package datadog.trace.instrumentation.synapse3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.synapse3.SynapseClientDecorator.SYNAPSE_CONTEXT_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.axis2.context.MessageContext;
import org.apache.http.nio.NHttpServerConnection;

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

      // Propagate the server span to the client via the message context.
      // Prefer reading the span directly from the source connection's context (where
      // SynapseServerInstrumentation stored it) over activeSpan(). SourceHandler dispatches
      // request processing to a worker thread pool, and while java-concurrent instrumentation
      // normally propagates context across ThreadPoolExecutor, the connection-based lookup is
      // more robust as it doesn't depend on automatic context propagation.
      AgentSpan span = null;
      Object sourceConn = message.getProperty("pass-through.Source-Connection");
      if (sourceConn instanceof NHttpServerConnection) {
        Object ctx =
            ((NHttpServerConnection) sourceConn).getContext().getAttribute(SYNAPSE_CONTEXT_KEY);
        if (ctx instanceof Context) {
          span = spanFromContext((Context) ctx);
        }
      }
      if (null == span) {
        span = activeSpan();
      }
      if (null != span) {
        message.setNonReplicableProperty(SYNAPSE_CONTEXT_KEY, span);
      }
    }
  }
}
