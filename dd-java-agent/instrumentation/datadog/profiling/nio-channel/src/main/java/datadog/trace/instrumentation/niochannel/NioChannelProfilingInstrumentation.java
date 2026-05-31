package datadog.trace.instrumentation.niochannel;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK;
import static datadog.trace.api.config.ProfilingConfig.PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import net.bytebuddy.asm.Advice;

/**
 * Brackets blocking NIO channel operations with {@code datadog.TaskBlock} intervals.
 *
 * <p><b>Activation.</b> This instrumentation is opt-in: it is active only when the Datadog wall
 * clock profiler pre-check is enabled ({@code profiling.ddprof.wall.precheck=true}, default {@code
 * false}). This matches the activation contract of all sibling Java TaskBlock instrumentations
 * (lock-support, thread-sleep, nio-selector).
 *
 * <p><b>Covered operations:</b>
 *
 * <ul>
 *   <li>{@link java.nio.channels.ServerSocketChannel#accept()} — blocks until an incoming
 *       connection arrives (blocking mode). In non-blocking mode the method returns {@code null}
 *       immediately; the 1 ms duration gate in {@code TaskBlockHelper.finish()} suppresses any
 *       sub-millisecond emissions, so no explicit guard is needed.
 *   <li>{@link java.nio.channels.SocketChannel#read(java.nio.ByteBuffer)
 *       SocketChannel.read(ByteBuffer)} - blocks until data is available on a blocking-mode socket
 *       channel.
 *   <li>{@link java.nio.channels.SocketChannel#read(java.nio.ByteBuffer[], int, int)
 *       SocketChannel.read(ByteBuffer[], int, int)} - scattering read variant.
 *   <li>{@link java.nio.channels.SocketChannel#write(java.nio.ByteBuffer)
 *       SocketChannel.write(ByteBuffer)} - blocks until data can be written on a blocking-mode
 *       socket channel.
 *   <li>{@link java.nio.channels.SocketChannel#write(java.nio.ByteBuffer[], int, int)
 *       SocketChannel.write(ByteBuffer[], int, int)} - gathering write variant.
 * </ul>
 *
 * <p><b>Target classes.</b> {@code sun.nio.ch.ServerSocketChannelImpl} and {@code
 * sun.nio.ch.SocketChannelImpl} are the HotSpot concrete implementations of the corresponding
 * abstract channel classes. Both are loaded into the bootstrap class loader before the Java agent
 * attaches, so {@link Instrumenter.ForKnownTypes} (retransformation at attach) is used.
 *
 * <p><b>Overhead.</b> {@code accept()} is called at most at connection-arrival rate - overhead is
 * negligible. {@code read}/{@code write} include an {@code isBlocking()} guard (~5 ns) that returns
 * {@code null} immediately for non-blocking channels (the dominant pattern in Netty and
 * Reactor-Netty event loops), making the per-call cost negligible for those frameworks. Blocking
 * channels (thread-per-connection servers) incur the full capture/finish cost only when the call
 * actually blocks, gated by the 1 ms threshold in {@link
 * TaskBlockHelper#finish(TaskBlockHelper.State)}.
 */
@AutoService(InstrumenterModule.class)
public class NioChannelProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public NioChannelProfilingInstrumentation() {
    super("nio-channel");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && Config.get().isDatadogProfilerEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                PROFILING_DATADOG_PROFILER_WALL_PRECHECK,
                PROFILING_DATADOG_PROFILER_WALL_PRECHECK_DEFAULT);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"sun.nio.ch.ServerSocketChannelImpl", "sun.nio.ch.SocketChannelImpl"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // AcceptAdvice fires only on ServerSocketChannelImpl (the only knownMatchingType with
    // an accept() method). The matcher produces zero matches on SocketChannelImpl.
    transformer.applyAdvice(
        isMethod().and(named("accept")).and(takesArguments(0)),
        getClass().getName() + "$AcceptAdvice");

    // ReadWriteAdvice fires only on SocketChannelImpl (the only knownMatchingType with
    // read/write methods).
    transformer.applyAdvice(
        isMethod()
            .and(named("read").or(named("write")))
            .and(
                takesArguments(1)
                    .and(takesArgument(0, named("java.nio.ByteBuffer")))
                    .or(
                        takesArguments(3)
                            .and(takesArgument(0, ByteBuffer[].class))
                            .and(takesArgument(1, int.class))
                            .and(takesArgument(2, int.class)))),
        getClass().getName() + "$ReadWriteAdvice");
  }

  /** Advice for {@code ServerSocketChannelImpl.accept()}. */
  public static final class AcceptAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TaskBlockHelper.State before() {
      // Blocker key is 0: the server socket accepts from any client; there is no single
      // file-descriptor identity to record here.
      return TaskBlockHelper.captureForIo(0L);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter TaskBlockHelper.State state) {
      TaskBlockHelper.finish(state);
    }
  }

  /** Advice for {@code SocketChannelImpl.read(...)} and {@code write(...)}. */
  public static final class ReadWriteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TaskBlockHelper.State before(@Advice.This SelectableChannel channel) {
      // Non-blocking channels return immediately from read/write. Skip capture to avoid
      // the per-call overhead (~50 ns) on high-frequency event-loop paths (Netty, etc.).
      if (!channel.isBlocking()) {
        return null;
      }
      // Use the channel's identity hash as the blocker key so that concurrent blocking reads
      // on different sockets produce distinct TaskBlock events in the backend.
      return TaskBlockHelper.captureForIo(System.identityHashCode(channel));
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter TaskBlockHelper.State state) {
      TaskBlockHelper.finish(state);
    }
  }
}
