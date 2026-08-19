// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.nioselect;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AROUND;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Brackets {@link java.nio.channels.Selector#select()}/{@code select(long)} call sites in Netty's
 * own NIO event loop with a synchronous {@code datadog.TaskBlock} interval.
 *
 * <p>Scoped to Netty's own event-loop callers only, not arbitrary application callers: Netty never
 * runs its event loop on a virtual thread, so every bracketed call is guaranteed to be a genuine
 * platform-OS-thread block. {@code selectNow()} is non-blocking and intentionally excluded.
 *
 * <p>Netty 4.1.x calls {@code Selector.select()}/{@code select(long)} directly from {@code
 * NioEventLoop}; Netty 4.2.x moved that call into a separate {@code NioIoHandler} class (used by
 * {@code SingleThreadIoEventLoop}). Both caller classes (plain and gRPC-shaded) are matched so this
 * instrumentation covers both Netty major versions.
 */
@AutoService(InstrumenterModule.class)
public class NioSelectProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForCallSite, Instrumenter.HasTypeAdvice {

  private static final String TASK_BLOCK_HELPER =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper";

  private static final String[] NIO_EVENT_LOOPS = {
    "io.netty.channel.nio.NioEventLoop",
    "io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoop",
    "io.netty.channel.nio.NioIoHandler",
    "io.grpc.netty.shaded.io.netty.channel.nio.NioIoHandler"
  };

  public NioSelectProfilingInstrumentation() {
    // This is part of wall-clock profiling, not a separately configurable tracing integration.
    super("wallclock");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance());
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return namedOneOf(NIO_EVENT_LOOPS);
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new CallSiteTransformer("nio-select", createAdvices()));
  }

  static Advices createAdvices() {
    return Advices.fromCallSites(new NioSelectCallSites());
  }

  public static final class NioSelectCallSites implements CallSites {
    @Override
    public void accept(Container container) {
      container.addAdvice(
          AROUND,
          "java/nio/channels/Selector",
          "select",
          "()I",
          (handler, opcode, owner, name, descriptor, isInterface) ->
              handler.advice(TASK_BLOCK_HELPER, "select", "(Ljava/nio/channels/Selector;)I"));
      container.addAdvice(
          AROUND,
          "java/nio/channels/Selector",
          "select",
          "(J)I",
          (handler, opcode, owner, name, descriptor, isInterface) ->
              handler.advice(TASK_BLOCK_HELPER, "select", "(Ljava/nio/channels/Selector;J)I"));
    }
  }
}
