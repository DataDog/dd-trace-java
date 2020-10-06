package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public abstract class FilteringExecutorInstrumentation extends Instrumenter.Default {

  private static final ElementMatcher.Junction<TypeDescription> PERMITTED =
      ElementMatchers.namedOneOf(
              // untested (should be able to test these in akka-concurrent)
              "akka.actor.ActorSystemImpl$$anon$1",
              "akka.dispatch.BalancingDispatcher",
              "akka.dispatch.Dispatcher",
              "akka.dispatch.Dispatcher$LazyExecutorServiceDelegate",
              "akka.dispatch.ExecutionContexts$sameThreadExecutionContext$",
              "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinPool",
              "akka.dispatch.MessageDispatcher",
              "akka.dispatch.PinnedDispatcher",
              // tested
              "com.google.common.util.concurrent.AbstractListeningExecutorService",
              "com.google.common.util.concurrent.MoreExecutors$ListeningDecorator",
              "com.google.common.util.concurrent.MoreExecutors$ScheduledListeningDecorator",
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
              "io.netty.util.concurrent.UnorderedThreadPoolEventExecutor",
              "io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoop",
              "io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup",
              "io.grpc.netty.shaded.io.netty.channel.MultithreadEventLoopGroup",
              "io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoop",
              "io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup",
              "io.grpc.netty.shaded.io.netty.channel.SingleThreadEventLoop",
              "io.grpc.netty.shaded.io.netty.util.concurrent.AbstractEventExecutor",
              "io.grpc.netty.shaded.io.netty.util.concurrent.AbstractEventExecutorGroup",
              "io.grpc.netty.shaded.io.netty.util.concurrent.AbstractScheduledEventExecutor",
              "io.grpc.netty.shaded.io.netty.util.concurrent.DefaultEventExecutor",
              "io.grpc.netty.shaded.io.netty.util.concurrent.DefaultEventExecutorGroup",
              "io.grpc.netty.shaded.io.netty.util.concurrent.GlobalEventExecutor",
              "io.grpc.netty.shaded.io.netty.util.concurrent.MultithreadEventExecutorGroup",
              "io.grpc.netty.shaded.io.netty.util.concurrent.SingleThreadEventExecutor",
              "io.grpc.netty.shaded.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor",
              "com.couchbase.client.deps.io.netty.channel.epoll.EpollEventLoop",
              "com.couchbase.client.deps.io.netty.channel.epoll.EpollEventLoopGroup",
              "com.couchbase.client.deps.io.netty.channel.MultithreadEventLoopGroup",
              "com.couchbase.client.deps.io.netty.channel.nio.NioEventLoop",
              "com.couchbase.client.deps.io.netty.channel.nio.NioEventLoopGroup",
              "com.couchbase.client.deps.io.netty.channel.SingleThreadEventLoop",
              "com.couchbase.client.deps.io.netty.util.concurrent.AbstractEventExecutor",
              "com.couchbase.client.deps.io.netty.util.concurrent.AbstractEventExecutorGroup",
              "com.couchbase.client.deps.io.netty.util.concurrent.AbstractScheduledEventExecutor",
              "com.couchbase.client.deps.io.netty.util.concurrent.DefaultEventExecutor",
              "com.couchbase.client.deps.io.netty.util.concurrent.DefaultEventExecutorGroup",
              "com.couchbase.client.deps.io.netty.util.concurrent.GlobalEventExecutor",
              "com.couchbase.client.deps.io.netty.util.concurrent.MultithreadEventExecutorGroup",
              "com.couchbase.client.deps.io.netty.util.concurrent.SingleThreadEventExecutor",
              "com.couchbase.client.deps.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor",
              "org.jboss.netty.channel.epoll.EpollEventLoop",
              "org.jboss.netty.channel.epoll.EpollEventLoopGroup",
              "org.jboss.netty.channel.MultithreadEventLoopGroup",
              "org.jboss.netty.channel.nio.NioEventLoop",
              "org.jboss.netty.channel.nio.NioEventLoopGroup",
              "org.jboss.netty.channel.SingleThreadEventLoop",
              "org.jboss.netty.util.concurrent.AbstractEventExecutor",
              "org.jboss.netty.util.concurrent.AbstractEventExecutorGroup",
              "org.jboss.netty.util.concurrent.AbstractScheduledEventExecutor",
              "org.jboss.netty.util.concurrent.DefaultEventExecutor",
              "org.jboss.netty.util.concurrent.DefaultEventExecutorGroup",
              "org.jboss.netty.util.concurrent.GlobalEventExecutor",
              "org.jboss.netty.util.concurrent.MultithreadEventExecutorGroup",
              "org.jboss.netty.util.concurrent.SingleThreadEventExecutor",
              "org.jboss.netty.util.concurrent.UnorderedThreadPoolEventExecutor",
              "java.util.concurrent.AbstractExecutorService",
              "java.util.concurrent.CompletableFuture$ThreadPerTaskExecutor",
              "java.util.concurrent.Executors$DelegatedExecutorService",
              "java.util.concurrent.Executors$FinalizableDelegatedExecutorService",
              "java.util.concurrent.ThreadPoolExecutor",
              "java.util.concurrent.ScheduledThreadPoolExecutor",
              // untested
              "kotlinx.coroutines.scheduling.CoroutineScheduler",
              // tested
              "org.apache.tomcat.util.threads.ThreadPoolExecutor",
              "org.eclipse.jetty.util.thread.MonitoredQueuedThreadPool",
              "org.eclipse.jetty.util.thread.QueuedThreadPool",
              "org.eclipse.jetty.util.thread.ReservedThreadExecutor",
              // untested
              "org.glassfish.grizzly.threadpool.GrizzlyExecutorService",
              "play.api.libs.streams.Execution$trampoline$",
              "scala.concurrent.Future$InternalCallbackExecutor$",
              "scala.concurrent.impl.ExecutionContextImpl")
          .or(nameStartsWith("slick.util.AsyncExecutor$"));

  public FilteringExecutorInstrumentation(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return PERMITTED.or(namedOneOf(Config.get().getTraceExecutors()));
  }
}
