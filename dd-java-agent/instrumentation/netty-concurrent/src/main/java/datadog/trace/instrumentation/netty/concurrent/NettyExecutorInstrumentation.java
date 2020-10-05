package datadog.trace.instrumentation.netty.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutionContext;
import datadog.trace.context.TraceScope;
import io.netty.util.concurrent.DefaultPromise;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class NettyExecutorInstrumentation extends Instrumenter.Default {
  public NettyExecutorInstrumentation() {
    super("netty-concurrent", "single-thread-event-executor");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return extendsClass(named("io.netty.util.concurrent.AbstractEventExecutor"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(8);
    transformers.put(
        isMethod().and(named("execute")).and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Wrap");
    return transformers;
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Runnable runnable) {
      if (null != runnable && !(runnable instanceof DefaultPromise)) {
        TraceScope scope = activeScope();
        if (null != scope) {
          runnable = ExecutionContext.wrap(scope, runnable);
        }
      }
    }
  }
}
