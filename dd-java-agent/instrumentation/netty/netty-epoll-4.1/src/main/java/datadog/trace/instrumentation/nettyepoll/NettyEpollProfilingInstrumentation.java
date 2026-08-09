// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.nettyepoll;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import net.bytebuddy.asm.Advice;

/**
 * Brackets Netty's native-epoll event loop wait calls with a synchronous {@code datadog.TaskBlock}
 * interval.
 *
 * <p>Targets the private wait methods ({@code epollWait}, {@code epollWaitNow}, {@code
 * epollWaitNoTimerChange}, {@code epollWaitTimeboxed}, {@code epollBusyWait}) rather than {@code
 * Native}'s static methods: which {@code Native.epollWait} overload is called, and its descriptor,
 * both drift across Netty versions (a package-private, 6-arg, threshold-aware overload replaced the
 * public 5-arg one around 4.1.53), but these private method names are stable across versions.
 * Matching by method name (not descriptor) and instrumenting the caller's own method also reaches
 * package-private overloads used by busy-loop/low-latency configurations, which an external
 * call-site rewrite of {@code Native} could not.
 *
 * <p>Netty 4.1.x declares these methods directly on {@code EpollEventLoop}; Netty 4.2.x moved the
 * wait loop into a separate {@code EpollIoHandler} class (used by {@code SingleThreadIoEventLoop}),
 * keeping the same method names. Both classes are matched so this instrumentation covers both Netty
 * major versions.
 *
 * <p>The advice itself references no Netty types, so the identical advice class also covers gRPC's
 * shaded copy of the same classes, matching the class-list pattern already established by {@code
 * EnableWallclockProfilingInstrumentation}.
 *
 * <p>Scoped to Netty's own event-loop classes only (never arbitrary application code): Netty never
 * runs its event loop on a virtual thread, so every bracketed call is guaranteed to be a genuine
 * platform-OS-thread block.
 */
@AutoService(InstrumenterModule.class)
public class NettyEpollProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String[] EPOLL_EVENT_LOOPS = {
    "io.netty.channel.epoll.EpollEventLoop",
    "io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoop",
    "io.netty.channel.epoll.EpollIoHandler",
    "io.grpc.netty.shaded.io.netty.channel.epoll.EpollIoHandler"
  };

  public NettyEpollProfilingInstrumentation() {
    super("netty-epoll");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance());
  }

  @Override
  public String[] knownMatchingTypes() {
    return EPOLL_EVENT_LOOPS;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(nameStartsWith("epollWait").or(named("epollBusyWait")))
            .and(isDeclaredBy(namedOneOf(EPOLL_EVENT_LOOPS))),
        getClass().getName() + "$EpollWaitAdvice");
  }

  public static final class EpollWaitAdvice {
    /** Starts a TaskBlock interval before the native epoll wait. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long before() {
      return TaskBlockHelper.begin(TaskBlockHelper.profiling());
    }

    /** Completes the TaskBlock interval after the native epoll wait returns. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter long token) {
      TaskBlockHelper.finish(TaskBlockHelper.profiling(), token);
    }
  }
}
