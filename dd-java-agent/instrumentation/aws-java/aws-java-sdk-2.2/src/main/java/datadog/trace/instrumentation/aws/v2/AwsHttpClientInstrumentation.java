package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closeActive;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.core.internal.http.pipeline.stages.MakeAsyncHttpRequestStage;

/**
 * Separate instrumentation class to close aws request scope right after request has been submitted
 * for execution for Sync clients.
 */
public final class AwsHttpClientInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "software.amazon.awssdk.core.internal.http.pipeline.stages.MakeHttpRequestStage";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("software.amazon.awssdk.")
        .and(
            extendsClass(
                namedOneOf(
                    "software.amazon.awssdk.core.internal.http.pipeline.stages.MakeHttpRequestStage",
                    "software.amazon.awssdk.core.internal.http.pipeline.stages.MakeAsyncHttpRequestStage")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("execute"))
            .and(
                takesArgument(
                    1, named("software.amazon.awssdk.core.internal.http.RequestExecutionContext"))),
        AwsHttpClientInstrumentation.class.getName() + "$AwsHttpClientAdvice");
  }

  public static class AwsHttpClientAdvice {
    // scope.close here doesn't actually finish the span.

    /**
     * FIXME: This is a hack to prevent netty instrumentation from messing things up.
     *
     * <p>Currently netty instrumentation cannot handle way AWS SDK makes http requests. If AWS SDK
     * make a netty call with active scope then continuation will be created that would never be
     * closed preventing whole trace from reporting. This happens because netty switches channels
     * between connection and request stages and netty instrumentation cannot find continuation
     * stored in channel attributes.
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope methodEnter(
        @Advice.This final Object thiz,
        @Advice.Argument(1) final RequestExecutionContext requestExecutionContext) {
      final AgentSpan activeSpan = activeSpan();
      // check name in case TracingExecutionInterceptor failed to activate the span
      if (activeSpan != null
          && ((!activeSpan.isValid())
              || AwsSdkClientDecorator.DECORATE
                  .spanName(requestExecutionContext.executionAttributes())
                  .equals(activeSpan.getSpanName()))) {
        if (thiz instanceof MakeAsyncHttpRequestStage) {
          // close async legacy HTTP span to avoid Netty leak...
          closeActive(); // then drop-through and activate no-op span
        } else {
          // keep sync legacy HTTP span alive for duration of call
          return AgentTracer::closeActive;
        }
      }
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(@Advice.Enter final TraceScope scope) {
      scope.close();
    }

    /**
     * This is to make muzzle think we need TracingExecutionInterceptor to make sure we do not apply
     * this instrumentation when TracingExecutionInterceptor would not work.
     */
    public static void muzzleCheck() {
      TracingExecutionInterceptor.muzzleCheck();
    }
  }
}
