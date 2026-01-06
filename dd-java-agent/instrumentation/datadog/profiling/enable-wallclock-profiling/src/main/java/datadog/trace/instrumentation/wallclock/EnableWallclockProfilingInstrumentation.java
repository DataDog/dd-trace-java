package datadog.trace.instrumentation.wallclock;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Arrays;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class EnableWallclockProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public EnableWallclockProfilingInstrumentation() {
    super("wallclock");
  }

  private static final String[] RUNNABLE_EVENT_LOOPS = {
    // regular netty
    "io.netty.channel.ThreadPerChannelEventLoop",
    "io.netty.channel.nio.NioEventLoop",
    "io.netty.channel.epoll.EPollEventLoop",
    "io.netty.channel.kqueue.KQueueEventLoop",
    // gRPC shades the same classes
    "io.grpc.netty.shaded.io.netty.channel.ThreadPerChannelEventLoop",
    "io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoop",
    "io.grpc.netty.shaded.io.netty.channel.epoll.EPollEventLoop",
    "io.grpc.netty.shaded.io.netty.channel.kqueue.KQueueEventLoop"
  };

  @Override
  public boolean isEnabled() {
    // only needed if wallclock profiling is enabled, which requires tracing
    return super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                PROFILING_DATADOG_PROFILER_WALL_ENABLED,
                PROFILING_DATADOG_PROFILER_WALL_ENABLED_DEFAULT)
        && InstrumenterConfig.get().isTraceEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String adviceClassName = getClass().getName() + "$EnableWallclockSampling";
    transformer.applyAdvice(
        isMethod()
            .and(
                named("run")
                    .and(isDeclaredBy(namedOneOf(RUNNABLE_EVENT_LOOPS)))
                    .and(takesNoArguments())),
        adviceClassName);
    transformer.applyAdvice(
        isMethod()
            .and(named("dowait"))
            .and(takesArguments(boolean.class, long.class))
            .and(isDeclaredBy(named("java.util.concurrent.CyclicBarrier"))),
        adviceClassName);
    transformer.applyAdvice(
        isMethod()
            .and(named("await"))
            .and(isDeclaredBy(named("java.util.concurrent.CountDownLatch"))),
        adviceClassName);
  }

  @Override
  public String[] knownMatchingTypes() {
    String[] all = Arrays.copyOf(RUNNABLE_EVENT_LOOPS, RUNNABLE_EVENT_LOOPS.length + 2);
    all[RUNNABLE_EVENT_LOOPS.length] = "java.util.concurrent.CyclicBarrier";
    all[RUNNABLE_EVENT_LOOPS.length + 1] = "java.util.concurrent.CountDownLatch";
    return all;
  }

  public static final class EnableWallclockSampling {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean before() {
      AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        AgentTracer.get().getProfilingContext().onAttach();
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter boolean wasDisabled) {
      if (wasDisabled) {
        AgentTracer.get().getProfilingContext().onDetach();
      }
    }
  }
}
