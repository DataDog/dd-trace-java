package datadog.trace.instrumentation.netty4.promise;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class NettyPromiseInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public NettyPromiseInstrumentation() {
    super("netty-promise");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "io.netty.util.concurrent.DefaultPromise";
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("addListener")
            .and(takesArgument(0, named("io.netty.util.concurrent.GenericFutureListener"))),
        NettyPromiseInstrumentation.class.getName() + "$WrapListenerAdvice");
    transformation.applyAdvice(
        named("addListeners")
            .and(takesArgument(0, named("io.netty.util.concurrent.GenericFutureListener[]"))),
        NettyPromiseInstrumentation.class.getName() + "$WrapListenersAdvice");
  }

  public static class WrapListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapListener(
        @Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>> listener) {
      listener = ListenerWrapper.wrapIfNeeded(listener);
    }
  }

  public static class WrapListenersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapListener(
        @Advice.Argument(value = 0, readOnly = false)
            GenericFutureListener<? extends Future<?>>[] listeners) {
      for (int i = 0; i < listeners.length; i++) {
        listeners[i] = ListenerWrapper.wrapIfNeeded(listeners[i]);
      }
    }
  }
}
