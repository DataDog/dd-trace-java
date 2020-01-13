package datadog.trace.instrumentation.twitterpromise;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import com.twitter.util.Promise;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.context.TraceScope;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.Function1;
import scala.runtime.AbstractFunction1;

@AutoService(Instrumenter.class)
public class TwitterPromiseInstrumentation extends Instrumenter.Default {
  public TwitterPromiseInstrumentation() {
    super("twitter-promise");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      TwitterPromiseInstrumentation.class.getName() + "$WrapFunction",
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
        isMethod().and(named("respond")),
        TwitterPromiseInstrumentation.class.getName() + "$WrapAdvice");

    map.put(
        isMethod().and(named("transform")),
        TwitterPromiseInstrumentation.class.getName() + "$WrapAdvice");

    return map;
  }

  public static class WrapAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapRespond(
        @Advice.This final Promise promise,
        @Advice.Argument(value = 0, readOnly = false) Function1 function1) {

      final TraceScope.Continuation continuation = propagate().capture();

      if (continuation != null) {
        function1 = new WrapFunction(function1, continuation);
      }
    }
  }

  public static class WrapFunction extends AbstractFunction1 {
    private final Function1 delegate;
    private final TraceScope.Continuation continuation;

    public WrapFunction(final Function1 delegate, final TraceScope.Continuation continuation) {
      this.delegate = delegate;
      this.continuation = continuation;
    }

    @Override
    public Object apply(final Object value) {
      try (final TraceScope scope = continuation.activate()) {
        return delegate.apply(value);
      }
    }
  }
}
