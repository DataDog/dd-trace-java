package datadog.trace.instrumentation.nioselector;

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
import net.bytebuddy.asm.Advice;

/**
 * Brackets all blocking {@link java.nio.channels.Selector#select() Selector.select()} variants with
 * a {@code datadog.TaskBlock} interval for NIO multiplexed I/O, which is the dominant blocking
 * pattern in reactive frameworks (Netty event loops, Vert.x, Reactor-Netty, etc.).
 *
 * <p><b>Covered overloads:</b>
 *
 * <ul>
 *   <li>{@link java.nio.channels.Selector#select() select()} — indefinitely blocking
 *   <li>{@link java.nio.channels.Selector#select(long) select(long timeout)} — blocking with
 *       timeout
 *   <li>{@link java.nio.channels.Selector#select(java.util.function.Consumer) select(Consumer)}
 *       (JDK 11+) — blocking, processes ready keys inline
 *   <li>{@link java.nio.channels.Selector#select(java.util.function.Consumer, long)
 *       select(Consumer, long timeout)} (JDK 11+) — blocking with timeout
 * </ul>
 *
 * <p><b>Excluded overloads:</b> {@code selectNow()} and {@code selectNow(Consumer)} are
 * non-blocking by contract — instrumenting them would add a capture/finish pair to every event-loop
 * tick in reactive frameworks (potentially 10^5/s), a meaningful overhead even though {@code
 * TaskBlockHelper}'s fast-path skips the JFR emit when no span is active.
 *
 * <p>The {@code TaskBlockHelper} short-circuits when no active span is present, so untraced event
 * loops carry only the ~20 ns TLS lookup and {@code activeSpan() == null} check per call.
 *
 * <p><b>Target class.</b> {@code sun.nio.ch.SelectorImpl} overrides <em>all</em> {@code select*}
 * variants — including the consumer-based {@code select(Consumer)} and {@code select(Consumer,
 * long)} added in JDK 11 — as {@code final} methods that each call {@code lockAndDoSelect(Consumer,
 * long)} directly. The platform-specific impls ({@code KQueueSelectorImpl}, {@code
 * EPollSelectorImpl}, {@code WindowsSelectorImpl}, {@code WEPollSelectorImpl}) extend {@code
 * SelectorImpl} and inherit these overrides. A single {@code SelectorImpl} target therefore covers
 * all blocking overloads. {@code SelectorImpl} is loaded into the bootstrap class loader before the
 * Java agent attaches, so {@link Instrumenter.ForKnownTypes} (retransformation at agent attach) is
 * used rather than {@code ForTypeHierarchy} (which only fires on subsequent class-load events).
 */
@AutoService(InstrumenterModule.class)
public class NioSelectorProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public NioSelectorProfilingInstrumentation() {
    super("nio-selector");
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
    // SelectorImpl overrides all select* variants as final (including consumer-based ones),
    // so a single target covers all blocking overloads.
    return new String[] {"sun.nio.ch.SelectorImpl"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("select"))
            // select() — indefinitely blocking, 0 args
            .and(
                takesArguments(0)
                    // select(long timeout)
                    .or(takesArguments(1).and(takesArgument(0, long.class)))
                    // select(Consumer<SelectionKey>) — JDK 11+
                    .or(
                        takesArguments(1)
                            .and(takesArgument(0, named("java.util.function.Consumer"))))
                    // select(Consumer<SelectionKey>, long timeout) — JDK 11+
                    .or(
                        takesArguments(2)
                            .and(takesArgument(0, named("java.util.function.Consumer")))
                            .and(takesArgument(1, long.class)))),
        getClass().getName() + "$SelectAdvice");
  }

  public static final class SelectAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TaskBlockHelper.State before() {
      // captureForIo(0L) — blocker key is 0 because Selector.select watches a set of file
      // descriptors, not a single one; the population-level distinction is left to the
      // call-site context.
      return TaskBlockHelper.captureForIo(0L);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter TaskBlockHelper.State state) {
      TaskBlockHelper.finish(state);
    }
  }
}
