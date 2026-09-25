package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.async.SuppressAsyncPropagationAdvice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Sometimes classes do lazy initialization for scheduling of tasks. If this is done during a trace
 * it can cause the trace to never be reported. These rules disable async propagation during this
 * period. New library rules belong beside their instrumentation and should extend {@link
 * datadog.trace.agent.tooling.async.AsyncPropagationSuppressingInstrumentation}.
 */
@AutoService(InstrumenterModule.class)
public final class AsyncPropagatingDisableInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public AsyncPropagatingDisableInstrumentation() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
  }

  private static final ElementMatcher<TypeDescription> NETTY_UNSAFE =
      namedOneOf(
          "io.netty.channel.nio.AbstractNioChannel$AbstractNioUnsafe",
          "io.grpc.netty.shaded.io.netty.channel.nio.AbstractNioChannel$AbstractNioUnsafe",
          "io.netty.channel.epoll.AbstractEpollChannel$AbstractEpollUnsafe",
          "io.grpc.netty.shaded.io.netty.channel.epoll.AbstractEpollChannel$AbstractEpollUnsafe",
          "io.netty.channel.kqueue.AbstractKQueueChannel$AbstractKQueueUnsafe",
          "io.grpc.netty.shaded.io.netty.channel.kqueue.AbstractKQueueChannel$AbstractKQueueUnsafe");
  private static final ElementMatcher<TypeDescription> GRPC_MANAGED_CHANNEL =
      nameEndsWith("io.grpc.internal.ManagedChannelImpl");
  private static final ElementMatcher.Junction<TypeDescription> REACTOR_DISABLED_TYPE_INITIALIZERS =
      namedOneOf("reactor.core.scheduler.SchedulerTask", "reactor.core.scheduler.WorkerTask");
  private static final ElementMatcher<TypeDescription> RXJAVA2_DISABLED_TYPE_INITIALIZERS =
      named("io.reactivex.internal.schedulers.AbstractDirectTask");

  private static final ElementMatcher<TypeDescription> NETTY_GLOBAL_EVENT_EXECUTOR =
      namedOneOf(
          "io.netty.util.concurrent.GlobalEventExecutor",
          "io.grpc.netty.shaded.io.netty.util.concurrent.GlobalEventExecutor",
          "com.couchbase.client.deps.io.netty.util.concurrent.GlobalEventExecutor");
  private static final ElementMatcher<TypeDescription> NETTY_IDLE_STATE_HANDLER =
      namedOneOf(
          "io.netty.handler.timeout.IdleStateHandler",
          "io.grpc.netty.shaded.io.netty.handler.timeout.IdleStateHandler");
  private static final ElementMatcher<TypeDescription> JAVA_HTTP_CLIENT =
      extendsClass(named("java.net.http.HttpClient"));
  private static final ElementMatcher<TypeDescription> PEKKO_HTTP_STREAM_STAGE =
      nameStartsWith("org.apache.pekko.http.impl.util.StreamUtils$")
          .and(extendsClass(named("org.apache.pekko.stream.stage.GraphStageLogic")));

  @Override
  public boolean onlyMatchKnownTypes() {
    return false; // known type list is not complete, so always expand search to consider hierarchy
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "com.amazonaws.http.timers.request.HttpRequestTimer",
      "io.netty.handler.timeout.WriteTimeoutHandler",
      "java.util.concurrent.ScheduledThreadPoolExecutor",
      "io.netty.channel.nio.AbstractNioChannel$AbstractNioUnsafe",
      "io.grpc.netty.shaded.io.netty.channel.nio.AbstractNioChannel$AbstractNioUnsafe",
      "io.netty.channel.epoll.AbstractEpollChannel$AbstractEpollUnsafe",
      "io.grpc.netty.shaded.io.netty.channel.epoll.AbstractEpollChannel$AbstractEpollUnsafe",
      "io.netty.channel.kqueue.AbstractKQueueChannel$AbstractKQueueUnsafe",
      "io.grpc.netty.shaded.io.netty.channel.kqueue.AbstractKQueueChannel$AbstractKQueueUnsafe",
      "io.grpc.internal.ServerImpl$ServerTransportListenerImpl",
      "okhttp3.ConnectionPool",
      "okhttp3.internal.connection.RealConnectionPool",
      "com.squareup.okhttp.ConnectionPool",
      "org.elasticsearch.transport.netty4.Netty4TcpChannel",
      "org.springframework.cglib.core.internal.LoadingCache",
      "com.datastax.oss.driver.internal.core.channel.DefaultWriteCoalescer$Flusher",
      "com.datastax.oss.driver.api.core.session.SessionBuilder",
      "org.jvnet.hk2.internal.ServiceLocatorImpl",
      "com.zaxxer.hikari.pool.HikariPool",
      "net.sf.ehcache.store.disk.DiskStorageFactory",
      "org.springframework.jms.listener.DefaultMessageListenerContainer",
      "org.apache.activemq.broker.TransactionBroker",
      "com.mongodb.internal.connection.DefaultConnectionPool$AsyncWorkManager",
      "io.reactivex.internal.schedulers.AbstractDirectTask",
      "jdk.internal.net.http.HttpClientImpl",
      "io.netty.util.concurrent.GlobalEventExecutor",
      "io.grpc.netty.shaded.io.netty.util.concurrent.GlobalEventExecutor",
      "com.couchbase.client.deps.io.netty.util.concurrent.GlobalEventExecutor",
      "io.netty.handler.timeout.IdleStateHandler",
      "io.grpc.netty.shaded.io.netty.handler.timeout.IdleStateHandler",
      "com.linecorp.armeria.client.HttpClientFactory",
      "com.linecorp.armeria.client.HttpChannelPool"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // no particular marker type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return REACTOR_DISABLED_TYPE_INITIALIZERS
        .or(GRPC_MANAGED_CHANNEL)
        .or(RXJAVA2_DISABLED_TYPE_INITIALIZERS)
        .or(JAVA_HTTP_CLIENT)
        .or(PEKKO_HTTP_STREAM_STAGE);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String advice = SuppressAsyncPropagationAdvice.class.getName();
    transformer.applyAdvice(named("connect").and(isDeclaredBy(NETTY_UNSAFE)), advice);
    transformer.applyAdvice(
        named("init")
            .and(isDeclaredBy(named("io.grpc.internal.ServerImpl$ServerTransportListenerImpl"))),
        advice);
    transformer.applyAdvice(
        named("startTimer")
            .and(isDeclaredBy(named("com.amazonaws.http.timers.request.HttpRequestTimer"))),
        advice);
    transformer.applyAdvice(
        named("scheduleTimeout")
            .and(isDeclaredBy(named("io.netty.handler.timeout.WriteTimeoutHandler"))),
        advice);
    transformer.applyAdvice(
        named("rescheduleIdleTimer").and(isDeclaredBy(GRPC_MANAGED_CHANNEL)), advice);
    transformer.applyAdvice(
        namedOneOf("scheduleAtFixedRate", "scheduleWithFixedDelay")
            .and(isDeclaredBy(named("java.util.concurrent.ScheduledThreadPoolExecutor"))),
        advice);
    transformer.applyAdvice(
        namedOneOf("addConnection", "put")
            .and(isDeclaredBy(named("com.squareup.okhttp.ConnectionPool"))),
        advice);
    transformer.applyAdvice(
        named("put").and(isDeclaredBy(named("okhttp3.ConnectionPool"))), advice);
    transformer.applyAdvice(
        named("put").and(isDeclaredBy(named("okhttp3.internal.connection.RealConnectionPool"))),
        advice);
    transformer.applyAdvice(
        named("sendMessage")
            .and(isDeclaredBy(named("org.elasticsearch.transport.netty4.Netty4TcpChannel"))),
        advice);
    transformer.applyAdvice(
        named("createEntry")
            .and(isDeclaredBy(named("org.springframework.cglib.core.internal.LoadingCache"))),
        advice);
    transformer.applyAdvice(
        named("runOnEventLoop")
            .and(
                isDeclaredBy(
                    named(
                        "com.datastax.oss.driver.internal.core.channel.DefaultWriteCoalescer$Flusher"))),
        advice);
    transformer.applyAdvice(
        named("buildAsync")
            .and(isDeclaredBy(named("com.datastax.oss.driver.api.core.session.SessionBuilder"))),
        advice);
    transformer.applyAdvice(
        namedOneOf("getInjecteeDescriptor", "getService")
            .and(isDeclaredBy(named("org.jvnet.hk2.internal.ServiceLocatorImpl"))),
        advice);
    transformer.applyAdvice(
        named("getConnection").and(isDeclaredBy(named("com.zaxxer.hikari.pool.HikariPool"))),
        advice);
    transformer.applyAdvice(
        named("schedule").and(isDeclaredBy(named("net.sf.ehcache.store.disk.DiskStorageFactory"))),
        advice);
    transformer.applyAdvice(
        named("doRescheduleTask")
            .and(
                isDeclaredBy(
                    named("org.springframework.jms.listener.DefaultMessageListenerContainer"))),
        advice);
    transformer.applyAdvice(
        named("beginTransaction")
            .and(isDeclaredBy(named("org.apache.activemq.broker.TransactionBroker"))),
        advice);
    transformer.applyAdvice(
        named("initUnlessClosed")
            .and(
                isDeclaredBy(
                    named(
                        "com.mongodb.internal.connection.DefaultConnectionPool$AsyncWorkManager"))),
        advice);
    transformer.applyAdvice(
        isTypeInitializer().and(isDeclaredBy(REACTOR_DISABLED_TYPE_INITIALIZERS)), advice);
    transformer.applyAdvice(
        isTypeInitializer().and(isDeclaredBy(RXJAVA2_DISABLED_TYPE_INITIALIZERS)), advice);
    transformer.applyAdvice(
        isTypeInitializer().and(isDeclaredBy(NETTY_GLOBAL_EVENT_EXECUTOR)), advice);
    transformer.applyAdvice(
        named("initialize")
            .and(returns(void.class))
            .and(
                takesArgument(
                    0,
                    namedOneOf(
                        "io.netty.channel.ChannelHandlerContext",
                        "io.grpc.netty.shaded.io.netty.channel.ChannelHandlerContext")))
            .and(isDeclaredBy(NETTY_IDLE_STATE_HANDLER)),
        advice);
    transformer.applyAdvice(namedOneOf("sendAsync").and(isDeclaredBy(JAVA_HTTP_CLIENT)), advice);
    transformer.applyAdvice(
        named("preStart").and(takesNoArguments()).and(isDeclaredBy(PEKKO_HTTP_STREAM_STAGE)),
        advice);
    // armeria runs its own codec/pipeline, so the active request span captured during connection
    // pool creation and channel connect will have no consumers.
    transformer.applyAdvice(
        named("pool").and(isDeclaredBy(named("com.linecorp.armeria.client.HttpClientFactory"))),
        advice);
    transformer.applyAdvice(
        named("connect").and(isDeclaredBy(named("com.linecorp.armeria.client.HttpChannelPool"))),
        advice);
  }
}
