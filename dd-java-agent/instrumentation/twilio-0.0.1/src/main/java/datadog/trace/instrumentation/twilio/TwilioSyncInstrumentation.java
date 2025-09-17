package datadog.trace.instrumentation.twilio;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.twilio.TwilioClientDecorator.DECORATE;
import static datadog.trace.instrumentation.twilio.TwilioClientDecorator.TWILIO_SDK;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import com.twilio.Twilio;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Instrument the Twilio SDK to identify calls as a seperate service. */
@AutoService(InstrumenterModule.class)
public class TwilioSyncInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public TwilioSyncInstrumentation() {
    super("twilio-sdk");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.twilio.base.Resource"; // implies existence of Twilio service classes
  }

  /** Match any child class of the base Twilio service classes. */
  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(
        namedOneOf(
            "com.twilio.base.Creator",
            "com.twilio.base.Deleter",
            "com.twilio.base.Fetcher",
            "com.twilio.base.Reader",
            "com.twilio.base.Updater"));
  }

  /** Return the helper classes which will be available for use in instrumentation. */
  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TwilioClientDecorator", packageName + ".TwilioClientDecorator$1"
    };
  }

  /** Return bytebuddy transformers for instrumenting the Twilio SDK. */
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    /*
       We are listing out the main service calls on the Creator, Deleter, Fetcher, Reader, and
       Updater abstract classes. The isDeclaredBy() matcher did not work in the unit tests and
       we found that there were certain methods declared on the base class (particularly Reader),
       which we weren't interested in annotating.
    */
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(namedOneOf("create", "delete", "read", "fetch", "update")),
        TwilioSyncInstrumentation.class.getName() + "$TwilioClientAdvice");
  }

  /** Advice for instrumenting Twilio service classes. */
  public static class TwilioClientAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This final Object that, @Advice.Origin("#m") final String methodName) {

      // Ensure that we only create a span for the top-level Twilio client method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Twilio.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(TWILIO_SDK);
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(span, that, methodName);

      return activateSpan(span);
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final Object response) {
      if (scope == null) {
        return;
      }

      // If we have a scope (i.e. we were the top-level Twilio SDK invocation),
      final AgentSpan span = scope.span();
      try {
        DECORATE.onResult(span, response);
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
      } finally {
        scope.close();
        span.finish();
        CallDepthThreadLocalMap.reset(Twilio.class); // reset call depth count
      }
    }
  }
}
