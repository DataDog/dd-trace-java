package datadog.trace.instrumentation.netty4.promise;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class NettyPromiseInstrumentation extends Instrumenter.Tracing {

  public NettyPromiseInstrumentation() {
    super("netty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.netty.util.concurrent.DefaultPromise");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ListenerWrapper",
      packageName + ".ListenerWrapper$GenericWrapper",
      packageName + ".ListenerWrapper$GenericProgressiveWrapper",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        namedOneOf("addListener", "addListener0")
            .and(takesArgument(0, named("io.netty.util.concurrent.GenericFutureListener"))),
        NettyPromiseInstrumentation.class.getName() + "$WrapListenerAdvice");
  }

  public static class WrapListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapListener(
        @Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>> listener) {
      listener = ListenerWrapper.wrapIfNeeded(listener);
    }
  }
}
