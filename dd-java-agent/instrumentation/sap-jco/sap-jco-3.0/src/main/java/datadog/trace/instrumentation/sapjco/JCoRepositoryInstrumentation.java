package datadog.trace.instrumentation.sapjco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.sapjco.JCoClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments {@code JCoRepository.getFunctionTemplate(String)} — the metadata lookup JCo performs
 * before an RFC call. It names and finishes the span opened by {@link JCoMetadataInstrumentation}
 * when the lookup actually hits SAP (a cache miss), and marks it as an error when SAP has no such
 * function. On a cache hit no RFC is sent, so no span is created.
 */
public class JCoRepositoryInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.sap.conn.jco.JCoRepository";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("getFunctionTemplate"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        getClass().getName() + "$GetFunctionTemplateAdvice");
  }

  public static class GetFunctionTemplateAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int onEnter(@Advice.Argument(0) String name) {
      // Register the passport manager here too: a lookup for a non-existent function never reaches
      // JCoFunction.execute (where the client advice would register it), so the metadata RFC would
      // otherwise go out before any manager exists and carry no passport.
      PassportRegistry.ensureClientRegistered();
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MetadataLookup.class);
      if (callDepth == 0) {
        MetadataLookup.begin(name);
      }
      return callDepth;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter int callDepth,
        @Advice.Thrown Throwable throwable,
        @Advice.Return Object template) {
      if (callDepth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(MetadataLookup.class);
      MetadataLookup lookup = MetadataLookup.current();
      MetadataLookup.end();
      if (lookup == null) {
        return;
      }
      AgentSpan span = lookup.getSpan();
      if (span == null) {
        return; // cache hit: no RFC was sent, nothing to report
      }
      if (throwable != null) {
        DECORATE.onRfcError(span, throwable);
      } else if (template == null) {
        DECORATE.onFunctionNotFound(span, lookup.getFunctionName());
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
