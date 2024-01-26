package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import software.amazon.awssdk.core.internal.http.RequestExecutionContext;
import software.amazon.awssdk.core.internal.http.pipeline.stages.MakeAsyncHttpRequestStage;

/**
 * Separate instrumentation class to close aws request scope right after request has been submitted
 * for execution for Sync clients.
 */
@AutoService(Instrumenter.class)
public final class AwsHttpClientInstrumentation extends AbstractAwsClientInstrumentation
    implements Instrumenter.ForTypeHierarchy {

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
    public static AgentScope methodEnter(
        @Advice.This final Object thiz,
        @Advice.Argument(1) final RequestExecutionContext requestExecutionContext) {
      final AgentScope scope = activeScope();
      // check name in case TracingExecutionInterceptor failed to activate the span
      if (scope != null
          && (scope.span() instanceof AgentTracer.NoopAgentSpan
              || AwsSdkClientDecorator.DECORATE
                  .spanName(requestExecutionContext.executionAttributes())
                  .equals(scope.span().getSpanName()))) {
        if (thiz instanceof MakeAsyncHttpRequestStage) {
          scope.close(); // close async legacy HTTP span to avoid Netty leak
        } else {
          return scope; // keep sync legacy HTTP span alive for duration of call
        }
      }
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(@Advice.Enter final AgentScope scope) {
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
