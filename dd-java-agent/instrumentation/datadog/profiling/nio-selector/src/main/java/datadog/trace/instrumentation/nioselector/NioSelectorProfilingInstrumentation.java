package datadog.trace.instrumentation.nioselector;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import net.bytebuddy.asm.Advice;

/**
 * Brackets blocking {@link java.nio.channels.Selector#select() select()} / {@link
 * java.nio.channels.Selector#select(long) select(long)} calls with a {@code datadog.TaskBlock}
 * interval. Mirrors {@code lock-support} and {@code object-wait} but for NIO multiplexed I/O, which
 * is the dominant blocking pattern in reactive frameworks (Netty event loops, Vert.x,
 * Reactor-Netty, etc.).
 *
 * <p>Non-blocking variants are excluded by construction:
 *
 * <ul>
 *   <li>{@code selectNow()} is non-blocking by contract &mdash; instrumenting it would add a
 *       capture/finish pair to every event-loop tick in reactive frameworks (potentially 10^5/s), a
 *       meaningful overhead even though {@code TaskBlockHelper}'s fast-path skips the JFR emit.
 *   <li>{@code select(long, TimeUnit)} (added in JDK 21+) is intentionally not matched; {@code
 *       takesArguments(1)} limits coverage to {@code select()} and {@code select(long)}.
 * </ul>
 *
 * <p>The {@code TaskBlockHelper} short-circuits when no active span is present, so untraced event
 * loops carry only the ~20 ns TLS lookup and {@code activeSpan() == null} check per call.
 *
 * <p><b>Target class.</b> Since {@code java.nio.channels.spi.AbstractSelector} is abstract and the
 * concrete {@code select()} implementations live on {@code sun.nio.ch.SelectorImpl} — which is
 * loaded into the bootstrap class loader before the Java agent attaches — we cannot use {@code
 * ForTypeHierarchy} (which only fires on subsequent class-load events for matching types). Instead,
 * we use {@link Instrumenter.ForKnownTypes} which requests retransformation of the known JDK class
 * on agent attach. {@code SelectorImpl.select()} is {@code final} and is the single concrete entry
 * point used by all platform-specific impls ({@code KQueueSelectorImpl}, {@code EPollSelectorImpl},
 * {@code WindowsSelectorImpl}, {@code WEPollSelectorImpl}), so a single target is sufficient.
 */
@AutoService(InstrumenterModule.class)
public class NioSelectorProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public NioSelectorProfilingInstrumentation() {
    super("nio-selector");
  }

  @Override
  public boolean isEnabled() {
    // No JDK-version-specific behaviour, but pin to JDK 11+ to keep the surface aligned with the
    // rest of the wall-clock-supplement modules and avoid muzzle differences on JDK 8.
    return JavaVirtualMachine.isJavaVersionAtLeast(11) && super.isEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"sun.nio.ch.SelectorImpl"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("select"))
            // Exclude selectNow() — non-blocking, hot in reactive event loops.
            .and(not(named("selectNow")))
            // Match select() and select(long timeout) only. select(long, TimeUnit) (JDK 21+) is
            // filtered out by takesArguments(1) combined with takesArgument(0, long.class).
            .and(takesArguments(0).or(takesArguments(1).and(takesArgument(0, long.class)))),
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
