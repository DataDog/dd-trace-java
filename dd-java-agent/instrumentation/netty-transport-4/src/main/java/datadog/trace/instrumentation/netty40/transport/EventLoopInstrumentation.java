package datadog.trace.instrumentation.netty40.transport;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation ensures that threads netty may initiate syscalls from is wallclock-profiled,
 * whether spans propagate to the event loop or not.
 */
@AutoService(Instrumenter.class)
public class EventLoopInstrumentation extends Instrumenter.Profiling
    implements Instrumenter.ForKnownTypes {
  public EventLoopInstrumentation() {
    super("netty-transport", "netty-eventloop");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      // regular netty
      "io.netty.channel.ThreadPerChannelEventLoop",
      "io.netty.channel.nio.NioEventLoop",
      "io.netty.channel.epoll.EPollEventLoop",
      "io.netty.channel.kqueue.KQueueEventLoop",
      // gRPC shades the same classes
      "io.grpc.netty.shaded.io.netty.channel.ThreadPerChannelEventLoop",
      "io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoop",
      "io.grpc.netty.shaded.io.netty.channel.epoll.EPollEventLoop",
      "io.grpc.netty.shaded.io.netty.channel.kqueue.KQueueEventLoop",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("run").and(takesNoArguments())),
        getClass().getName() + "$ManageEventLoopThread");
  }

  private static final class ManageEventLoopThread {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before() {
      AgentTracer.get().getProfilingContext().onAttach();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after() {
      AgentTracer.get().getProfilingContext().onDetach();
    }
  }
}
