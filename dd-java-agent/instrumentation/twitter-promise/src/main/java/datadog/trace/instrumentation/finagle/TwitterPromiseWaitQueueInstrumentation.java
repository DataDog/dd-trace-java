package datadog.trace.instrumentation.finagle;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import com.twitter.util.Try;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class TwitterPromiseWaitQueueInstrumentation extends Instrumenter.Default {
  public TwitterPromiseWaitQueueInstrumentation() {
    super("twitter-promise");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.twitter.util.Promise$WaitQueue");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TwitterPromiseUtils",
      packageName + ".TwitterPromiseUtils$ListenerWrapper",
      packageName + ".TwitterPromiseUtils$ContinuationSupplier"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(named("com$twitter$util$Promise$WaitQueue$$run")),
        TwitterPromiseWaitQueueInstrumentation.class.getName() + "$WaitQueueAdvice");
  }

  public static class WaitQueueAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void activateContinuation(@Advice.Argument(0) final Try tryInstance) {
      // System.out.println("Executing wait queue: " + tryInstance);
      TwitterPromiseUtils.activateContinuation(tryInstance);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, inline = false)
    public static void closeContinuation(@Advice.Argument(0) final Try tryInstance) {
      TwitterPromiseUtils.finishPromiseForTry(tryInstance);
      // System.out.println("Finished executing wait queue");
    }
  }
}
