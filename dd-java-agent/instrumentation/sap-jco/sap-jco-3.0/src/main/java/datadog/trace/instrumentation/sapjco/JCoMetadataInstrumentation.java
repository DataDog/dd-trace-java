package datadog.trace.instrumentation.sapjco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.sapjco.JCoClientDecorator.DECORATE;
import static datadog.trace.instrumentation.sapjco.JCoClientDecorator.METADATA_OPERATION;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

/**
 * Instruments the internal metadata-send path {@code ClientConnection.execute(JCoFunction,
 * RepositoryProvider)}. It runs only when a repository lookup actually hits SAP (a cache miss), so
 * this is where we open the metadata span — only then is a real RFC sent. The span is opened here
 * so it is active while the RFC goes out (letting the passport bind to it and join SAP into the
 * trace); it is finished by {@link JCoRepositoryInstrumentation}, which knows the queried name and
 * outcome.
 *
 * <p>Guarded by {@link MetadataLookup}: a span is created only inside an instrumented {@code
 * getFunctionTemplate} lookup, so business RFC calls through this class are untouched.
 */
public class JCoMetadataInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.sap.conn.jco.rt.ClientConnection";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("com.sap.conn.jco.JCoFunction"))),
        getClass().getName() + "$MetadataSendAdvice");
  }

  public static class MetadataSendAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      MetadataLookup lookup = MetadataLookup.current();
      if (lookup == null || lookup.getSpan() != null) {
        return;
      }
      AgentSpan span = startSpan("sap-jco", METADATA_OPERATION);
      DECORATE.afterStart(span);
      DECORATE.onMetadata(span, lookup.getFunctionName());
      lookup.setSpan(span);
      // Keep the span active for the rest of the lookup so every RFM it sends (not just this first
      // one) carries the passport. The scope is closed by the getFunctionTemplate advice.
      lookup.setScope(activateSpan(span));
    }
  }
}
