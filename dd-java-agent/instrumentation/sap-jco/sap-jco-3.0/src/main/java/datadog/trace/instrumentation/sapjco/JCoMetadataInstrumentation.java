package datadog.trace.instrumentation.sapjco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
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
 * this is where we open the metadata span — only then is a real RFC sent. The span is stored in
 * {@link MetadataLookup} (not activated); the passport manager reads it from there for every RFM of
 * the lookup, and {@link JCoRepositoryInstrumentation} finishes it with the queried name and
 * outcome.
 *
 * <p>Guarded by {@link MetadataLookup}: a span is created only inside an instrumented {@code
 * getFunctionTemplate} lookup, so business RFC calls through this class are untouched.
 *
 * <p><b>Fragile hook.</b> {@code com.sap.conn.jco.rt.ClientConnection} and its {@code
 * execute(JCoFunction, RepositoryProvider)} method are JCo internals, not public API, so a future
 * JCo version could rename or re-sign them. Muzzle does not guard this: it only checks symbols the
 * advice references in code, and the target here is a type/method matcher, not a code reference
 * (and there is no muzzle version matrix — sapjco3 is not published to any public repository). If
 * this stops matching, degradation is soft: the metadata RFC's passport falls back to the active
 * request span (so it stays in the trace), and we only lose this {@code sap.rfc.metadata} span and
 * the function-not-found marking. Business tracing ({@code sap.rfc.call}) hooks the public {@code
 * JCoFunction.execute(JCoDestination)} and is unaffected.
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
      // Not activated: the passport manager reads this span from MetadataLookup directly, so every
      // RFM of the lookup binds to it without holding a scope open across advice methods.
      lookup.setSpan(span);
    }
  }
}
