package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Sometimes classes do lazy initialization for scheduling of tasks. If this is done during a trace
 * it can cause the trace to never be reported. Add matchers below to disable async propagation
 * during this period.
 */
@AutoService(InstrumenterModule.class)
public final class AsyncPropagatingDisableInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  public AsyncPropagatingDisableInstrumentation() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
  }

  private static final ElementMatcher.Junction<TypeDescription> RX_WORKERS =
      nameStartsWith("rx.").and(extendsClass(named("rx.Scheduler$Worker")));
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
  private static final ElementMatcher<TypeDescription> REACTOR_DISABLED_TYPE_INITIALIZERS =
      namedOneOf("reactor.core.scheduler.SchedulerTask", "reactor.core.scheduler.WorkerTask");

  @Override
  public boolean onlyMatchKnownTypes() {
    return false; // known type list is not complete, so always expand search to consider hierarchy
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "rx.internal.operators.OperatorTimeoutBase",
      "com.amazonaws.http.timers.request.HttpRequestTimer",
      "io.netty.handler.timeout.WriteTimeoutHandler",
      "java.util.concurrent.ScheduledThreadPoolExecutor",
      "io.netty.channel.nio.AbstractNioChannel$AbstractNioUnsafe",
      "io.grpc.netty.shaded.io.netty.channel.nio.AbstractNioChannel$AbstractNioUnsafe",
      "io.netty.channel.epoll.AbstractEpollChannel$AbstractEpollUnsafe",
      "io.grpc.netty.shaded.io.netty.channel.epoll.AbstractEpollChannel$AbstractEpollUnsafe",
      "io.netty.channel.kqueue.AbstractKQueueChannel$AbstractKQueueUnsafe",
      "io.grpc.netty.shaded.io.netty.channel.kqueue.AbstractKQueueChannel$AbstractKQueueUnsafe",
      "rx.internal.util.ObjectPool",
      "io.grpc.internal.ServerImpl$ServerTransportListenerImpl",
      "okhttp3.ConnectionPool",
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
      "com.mongodb.internal.connection.DefaultConnectionPool$AsyncWorkManager"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // no particular marker type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return RX_WORKERS.or(GRPC_MANAGED_CHANNEL).or(REACTOR_DISABLED_TYPE_INITIALIZERS);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    String advice = getClass().getName() + "$DisableAsyncAdvice";
    transformer.applyAdvice(named("schedulePeriodically").and(isDeclaredBy(RX_WORKERS)), advice);
    transformer.applyAdvice(
        named("call").and(isDeclaredBy(named("rx.internal.operators.OperatorTimeoutBase"))),
        advice);
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
        named("start").and(isDeclaredBy(named("rx.internal.util.ObjectPool"))), advice);
    transformer.applyAdvice(
        named("addConnection").and(isDeclaredBy(named("com.squareup.okhttp.ConnectionPool"))),
        advice);
    transformer.applyAdvice(
        named("put").and(isDeclaredBy(named("okhttp3.ConnectionPool"))), advice);
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
  }

  public static class DisableAsyncAdvice {

    @Advice.OnMethodEnter
    public static boolean before() {
      if (isAsyncPropagationEnabled()) {
        setAsyncPropagationEnabled(false);
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter boolean wasDisabled) {
      if (wasDisabled) {
        setAsyncPropagationEnabled(true);
      }
    }
  }
}
