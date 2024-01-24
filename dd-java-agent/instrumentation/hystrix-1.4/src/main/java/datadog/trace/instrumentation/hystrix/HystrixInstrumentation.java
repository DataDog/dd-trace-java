package datadog.trace.instrumentation.hystrix;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import com.netflix.hystrix.HystrixInvokableInfo;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import rx.Observable;

@AutoService(Instrumenter.class)
public class HystrixInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public HystrixInstrumentation() {
    super("hystrix");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.netflix.hystrix.HystrixCommand";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(
        namedOneOf(
            "com.netflix.hystrix.HystrixCommand", "com.netflix.hystrix.HystrixObservableCommand"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "rx.DDTracingUtil",
      "datadog.trace.instrumentation.rxjava.SpanFinishingSubscription",
      "datadog.trace.instrumentation.rxjava.TracedSubscriber",
      "datadog.trace.instrumentation.rxjava.TracedOnSubscribe",
      packageName + ".HystrixDecorator",
      packageName + ".HystrixDecorator$1",
      packageName + ".HystrixDecorator$ResourceNameCacheKey",
      packageName + ".HystrixOnSubscribe",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getExecutionObservable").and(returns(named("rx.Observable"))),
        HystrixInstrumentation.class.getName() + "$ExecuteAdvice");
    transformer.applyAdvice(
        named("getFallbackObservable").and(returns(named("rx.Observable"))),
        HystrixInstrumentation.class.getName() + "$FallbackAdvice");
  }

  public static class ExecuteAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final HystrixInvokableInfo<?> command,
        @Advice.Return(readOnly = false) Observable result,
        @Advice.Thrown final Throwable throwable) {

      result = Observable.create(new HystrixOnSubscribe(result, command, "execute"));
    }
  }

  public static class FallbackAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final HystrixInvokableInfo<?> command,
        @Advice.Return(readOnly = false) Observable<?> result,
        @Advice.Thrown final Throwable throwable) {

      result = Observable.create(new HystrixOnSubscribe(result, command, "fallback"));
    }
  }
}
