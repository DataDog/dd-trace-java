package datadog.trace.instrumentation.okhttp2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.squareup.okhttp.Callback;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class CallInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public CallInstrumentation() {
    super("okhttp", "okhttp-2");
  }

  @Override
  public String instrumentedType() {
    return "com.squareup.okhttp.Call";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".TracingCallbackWrapper"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("enqueue"))
            .and(takesArgument(0, named("com.squareup.okhttp.Callback"))),
        CallInstrumentation.class.getName() + "$EnqueueAdvice");
  }

  public static class EnqueueAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapCallback(
        @Advice.Argument(value = 0, readOnly = false) Callback callback) {
      AgentSpan currentSpan = activeSpan();
      if (currentSpan != null) {
        // only inject continuation if a span is present
        callback = new TracingCallbackWrapper(callback, captureSpan(currentSpan));
      }
    }
  }
}
