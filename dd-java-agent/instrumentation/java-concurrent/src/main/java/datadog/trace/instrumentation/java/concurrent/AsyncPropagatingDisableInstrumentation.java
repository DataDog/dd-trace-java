package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Sometimes classes do lazy initialization for scheduling of tasks. If this is done during a trace
 * it can cause the trace to never be reported. Add matchers below to disable async propagation
 * during this period.
 */
@AutoService(Instrumenter.class)
public final class AsyncPropagatingDisableInstrumentation extends Instrumenter.Tracing {

  public AsyncPropagatingDisableInstrumentation() {
    super("java_concurrent");
  }

  private static final ElementMatcher<TypeDescription> RX_WORKERS =
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
      namedOneOf("reactor.core.scheduler.SchedulerTask", "reactor.core.scheduler.WorkerTask")
          .and(
              declaresField(
                  ElementMatchers.named("FINISHED")
                      .and(ElementMatchers.<FieldDescription>isStatic())))
          .and(
              declaresField(
                  ElementMatchers.named("CANCELLED")
                      .and(ElementMatchers.<FieldDescription>isStatic())));

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    // all set matchers are denormalised into this set to reduce the amount of matching
    // required to rule a type out
    return NameMatchers.<TypeDescription>namedOneOf(
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
            "com.mongodb.internal.connection.DefaultConnectionPool$AsyncWorkManager")
        .or(RX_WORKERS)
        .or(GRPC_MANAGED_CHANNEL)
        .or(REACTOR_DISABLED_TYPE_INITIALIZERS);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    String advice = getClass().getName() + "$DisableAsyncAdvice";
    transformation.applyAdvice(named("schedulePeriodically").and(isDeclaredBy(RX_WORKERS)), advice);
    transformation.applyAdvice(
        named("call").and(isDeclaredBy(named("rx.internal.operators.OperatorTimeoutBase"))),
        advice);
    transformation.applyAdvice(named("connect").and(isDeclaredBy(NETTY_UNSAFE)), advice);
    transformation.applyAdvice(
        named("init")
            .and(isDeclaredBy(named("io.grpc.internal.ServerImpl$ServerTransportListenerImpl"))),
        advice);
    transformation.applyAdvice(
        named("startTimer")
            .and(isDeclaredBy(named("com.amazonaws.http.timers.request.HttpRequestTimer"))),
        advice);
    transformation.applyAdvice(
        named("scheduleTimeout")
            .and(isDeclaredBy(named("io.netty.handler.timeout.WriteTimeoutHandler"))),
        advice);
    transformation.applyAdvice(
        named("rescheduleIdleTimer").and(isDeclaredBy(GRPC_MANAGED_CHANNEL)), advice);
    transformation.applyAdvice(
        namedOneOf("scheduleAtFixedRate", "scheduleWithFixedDelay")
            .and(isDeclaredBy(named("java.util.concurrent.ScheduledThreadPoolExecutor"))),
        advice);
    transformation.applyAdvice(
        named("start").and(isDeclaredBy(named("rx.internal.util.ObjectPool"))), advice);
    transformation.applyAdvice(
        named("addConnection").and(isDeclaredBy(named("com.squareup.okhttp.ConnectionPool"))),
        advice);
    transformation.applyAdvice(
        named("put").and(isDeclaredBy(named("okhttp3.ConnectionPool"))), advice);
    transformation.applyAdvice(
        named("sendMessage")
            .and(isDeclaredBy(named("org.elasticsearch.transport.netty4.Netty4TcpChannel"))),
        advice);
    transformation.applyAdvice(
        named("createEntry")
            .and(isDeclaredBy(named("org.springframework.cglib.core.internal.LoadingCache"))),
        advice);
    transformation.applyAdvice(
        named("runOnEventLoop")
            .and(
                isDeclaredBy(
                    named(
                        "com.datastax.oss.driver.internal.core.channel.DefaultWriteCoalescer$Flusher"))),
        advice);
    transformation.applyAdvice(
        named("buildAsync")
            .and(isDeclaredBy(named("com.datastax.oss.driver.api.core.session.SessionBuilder"))),
        advice);
    transformation.applyAdvice(
        namedOneOf("getInjecteeDescriptor", "getService")
            .and(isDeclaredBy(named("org.jvnet.hk2.internal.ServiceLocatorImpl"))),
        advice);
    transformation.applyAdvice(
        named("getConnection").and(isDeclaredBy(named("com.zaxxer.hikari.pool.HikariPool"))),
        advice);
    transformation.applyAdvice(
        named("schedule").and(isDeclaredBy(named("net.sf.ehcache.store.disk.DiskStorageFactory"))),
        advice);
    transformation.applyAdvice(
        named("doRescheduleTask")
            .and(
                isDeclaredBy(
                    named("org.springframework.jms.listener.DefaultMessageListenerContainer"))),
        advice);
    transformation.applyAdvice(
        named("beginTransaction")
            .and(isDeclaredBy(named("org.apache.activemq.broker.TransactionBroker"))),
        advice);
    transformation.applyAdvice(
        named("initUnlessClosed")
            .and(
                isDeclaredBy(
                    named(
                        "com.mongodb.internal.connection.DefaultConnectionPool$AsyncWorkManager"))),
        advice);
    transformation.applyAdvice(
        isTypeInitializer().and(isDeclaredBy(REACTOR_DISABLED_TYPE_INITIALIZERS)), advice);
  }

  public static class DisableAsyncAdvice {

    @Advice.OnMethodEnter
    public static AgentScope before() {
      AgentScope scope = activeScope();
      if (null != scope && scope.isAsyncPropagating()) {
        scope.setAsyncPropagation(false);
        return scope;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      if (null != scope) {
        scope.setAsyncPropagation(true);
      }
    }
  }
}
