package datadog.trace.instrumentation.twilio;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.twilio.TwilioClientDecorator.DECORATE;
import static datadog.trace.instrumentation.twilio.TwilioClientDecorator.TWILIO_SDK;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
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
public class TwilioAsyncInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public TwilioAsyncInstrumentation() {
    super("twilio-sdk");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Only apply instrumentation when guava's ListenableFuture is also deployed.
    return hasClassNamed("com.google.common.util.concurrent.ListenableFuture");
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
      packageName + ".TwilioClientDecorator",
      packageName + ".TwilioClientDecorator$1",
      packageName + ".SpanFinishingCallback",
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
        isMethod()
            .and(namedOneOf("createAsync", "deleteAsync", "readAsync", "fetchAsync", "updateAsync"))
            .and(isPublic())
            .and(returns(named("com.google.common.util.concurrent.ListenableFuture"))),
        TwilioAsyncInstrumentation.class.getName() + "$TwilioClientAsyncAdvice");
  }

  /** Advice for instrumenting Twilio service classes. */
  public static class TwilioClientAsyncAdvice {

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

      // Don't automatically close the span with the scope if we're executing an async method
      final AgentSpan span = startSpan(TWILIO_SDK);
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(span, that, methodName);

      // Enable async propagation, so the newly spawned task will be associated back with this
      // original trace.
      return activateSpan(span);
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final ListenableFuture response) {
      if (scope == null) {
        return;
      }
      // If we have a scope (i.e. we were the top-level Twilio SDK invocation),
      try {
        final AgentSpan span = scope.span();

        if (throwable != null) {
          // There was an synchronous error,
          // which means we shouldn't wait for a callback to close the span.
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
          span.finish();
        } else {
          // We're calling an async operation, we still need to finish the span when it's
          // complete and report the results; set an appropriate callback
          Futures.addCallback(
              response, new SpanFinishingCallback(span), Twilio.getExecutorService());
        }
      } finally {
        scope.close();
        // span finished in SpanFinishingCallback
        CallDepthThreadLocalMap.reset(Twilio.class); // reset call depth count
      }
    }
  }
}
