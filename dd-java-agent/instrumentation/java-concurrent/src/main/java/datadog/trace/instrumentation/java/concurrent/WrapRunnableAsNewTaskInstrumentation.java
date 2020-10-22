package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.java.concurrent.NewTaskFor.newTaskFor;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public final class WrapRunnableAsNewTaskInstrumentation extends Instrumenter.Default {
  public WrapRunnableAsNewTaskInstrumentation() {
    super("java_concurrent", "new-task-for");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
        "io.netty.channel.epoll.EpollEventLoop",
        "io.netty.channel.epoll.EpollEventLoopGroup",
        "io.netty.channel.MultithreadEventLoopGroup",
        "io.netty.channel.nio.NioEventLoop",
        "io.netty.channel.nio.NioEventLoopGroup",
        "io.netty.channel.SingleThreadEventLoop",
        "io.netty.util.concurrent.AbstractEventExecutor",
        "io.netty.util.concurrent.AbstractEventExecutorGroup",
        "io.netty.util.concurrent.AbstractScheduledEventExecutor",
        "io.netty.util.concurrent.DefaultEventExecutor",
        "io.netty.util.concurrent.DefaultEventExecutorGroup",
        "io.netty.util.concurrent.GlobalEventExecutor",
        "io.netty.util.concurrent.MultithreadEventExecutorGroup",
        "io.netty.util.concurrent.SingleThreadEventExecutor",
        "java.util.concurrent.AbstractExecutorService",
        "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor",
        "java.util.concurrent.SubmissionPublisher$ThreadPerTaskExecutor",
        "java.util.concurrent.ThreadPoolExecutor",
        "org.glassfish.grizzly.threadpool.GrizzlyExecutorService");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".NewTaskFor"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(
                named("execute")
                    .and(ElementMatchers.takesArgument(0, named(Runnable.class.getName())))),
        getClass().getName() + "$Wrap");
  }

  public static final class Wrap {
    @Advice.OnMethodEnter
    public static void execute(
        @Advice.This Executor executor,
        @Advice.Argument(value = 0, readOnly = false) Runnable task) {
      task = newTaskFor(executor, task);
    }

    @SuppressWarnings("rawtypes")
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void cancel(@Advice.Argument(0) Runnable task, @Advice.Thrown Throwable error) {
      if (null != error && task instanceof RunnableFuture) {
        ((RunnableFuture) task).cancel(true);
      }
    }
  }
}
