package datadog.trace.instrumentation.aws.v2;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import software.amazon.awssdk.core.internal.http.pipeline.stages.MakeAsyncHttpRequestStage;

/**
 * Separate instrumentation class to close aws request scope right after request has been submitted
 * for execution for Sync clients.
 */
@AutoService(Instrumenter.class)
public final class AwsHttpClientInstrumentation extends AbstractAwsClientInstrumentation {

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed(
        "software.amazon.awssdk.core.internal.http.pipeline.stages.MakeHttpRequestStage");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return nameStartsWith("software.amazon.awssdk.")
        .and(
            extendsClass(
                named(
                        "software.amazon.awssdk.core.internal.http.pipeline.stages.MakeHttpRequestStage")
                    .or(
                        named(
                            "software.amazon.awssdk.core.internal.http.pipeline.stages.MakeAsyncHttpRequestStage"))));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod().and(isPublic()).and(named("execute")),
        AwsHttpClientInstrumentation.class.getName() + "$AwsHttpClientAdvice");
  }

  public static class AwsHttpClientAdvice {
    // scope.close here doesn't actually close the span.

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
    public static boolean methodEnter(@Advice.This final Object thiz) {
      if (thiz instanceof MakeAsyncHttpRequestStage) {
        final TraceScope scope = activeScope();
        if (scope != null) {
          scope.close();
          return true;
        }
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(@Advice.Enter final boolean scopeAlreadyClosed) {
      if (!scopeAlreadyClosed) {
        final TraceScope scope = activeScope();
        if (scope != null) {
          scope.close();
        }
      }
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
