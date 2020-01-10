package datadog.trace.instrumentation.finagle;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.twitter.util.Future;
import com.twitter.util.Promise;
import com.twitter.util.Try;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class TwitterPromiseInstrumentation extends Instrumenter.Default {
  public TwitterPromiseInstrumentation() {
    super("twitter-promise");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TwitterPromiseUtils",
      packageName + ".TwitterPromiseUtils$ListenerWrapper",
      packageName + ".TwitterPromiseUtils$PromiseChain",
      packageName + ".TwitterPromiseUtils$PromiseChainSupplier",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return safeHasSuperType(named("com.twitter.util.Promise"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<MethodDescription>, String> map = new HashMap<>();
    map.put(
        isMethod().and(named("cas")).and(takesArguments(2)),
        TwitterPromiseInstrumentation.class.getName() + "$LinkingAdvice");
    map.put(
        isMethod().and(named("become")),
        TwitterPromiseInstrumentation.class.getName() + "$BecomingAdvice");
    map.put(
        isConstructor()
            .and(takesArguments(1))
            .and(takesArgument(0, named("com.twitter.util.Future"))),
        TwitterPromiseInstrumentation.class.getName() + "$ConstructorTransferAdvice");

    return map;
  }

  public static class LinkingAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void associateTryWithContinuation(
        @Advice.This final Promise promise,
        @Advice.Argument(1) final Object newState,
        @Advice.Return final boolean success) {

      // When the state is set to a Try (done state), link the Try to the continuation of the
      // original promise
      if (success) {
        if (newState instanceof Try) {
          TwitterPromiseUtils.linkTryToContinuation((Try) newState, promise);
        } else if (newState instanceof Promise) {
          TwitterPromiseUtils.copyContinuation((Promise) newState, promise);
        }
      }
    }
  }

  public static class ConstructorTransferAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void transferContinuationInConstructor(
        @Advice.This final Promise promise, @Advice.Argument(0) final Future otherFuture) {

      if (otherFuture instanceof Promise) {
        TwitterPromiseUtils.copyContinuation((Promise) otherFuture, promise);
      }
    }
  }

  public static class BecomingAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void printBecoming(
        @Advice.This final Promise promise, @Advice.Argument(0) final Future otherPromise) {

      System.out.println(promise.hashCode() + " becoming " + otherPromise.hashCode());
    }
  }
}
