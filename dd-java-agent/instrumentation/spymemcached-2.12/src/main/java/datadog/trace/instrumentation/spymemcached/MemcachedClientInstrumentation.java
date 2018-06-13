package datadog.trace.instrumentation.spymemcached;

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.DDTransformers;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Method;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.BulkFuture;
import net.spy.memcached.internal.GetFuture;
import net.spy.memcached.internal.OperationFuture;

@AutoService(Instrumenter.class)
public final class MemcachedClientInstrumentation extends Instrumenter.Configurable {

  private static final String MEMCACHED_PACKAGE = "net.spy.memcached";
  private static final String HELPERS_PACKAGE =
      MemcachedClientInstrumentation.class.getPackage().getName();

  public static final HelperInjector HELPER_INJECTOR =
      new HelperInjector(
          HELPERS_PACKAGE + ".DDTracingCompletionListener",
          HELPERS_PACKAGE + ".DDTracingGetCompletionListener",
          HELPERS_PACKAGE + ".DDTracingOperationCompletionListener",
          HELPERS_PACKAGE + ".DDTracingBulkCompletionListener");

  public MemcachedClientInstrumentation() {
    super("spymemcached");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named(MEMCACHED_PACKAGE + ".MemcachedClient"))
        .transform(HELPER_INJECTOR)
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(returns(named(MEMCACHED_PACKAGE + ".internal.OperationFuture"))),
                    AsyncOperationAdvice.class.getName()))
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(returns(named(MEMCACHED_PACKAGE + ".internal.GetFuture"))),
                    AsyncGetAdvice.class.getName()))
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(returns(named(MEMCACHED_PACKAGE + ".internal.BulkFuture"))),
                    AsyncBulkAdvice.class.getName()))
        .asDecorator();
  }

  public static class AsyncOperationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Boolean methodEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final Boolean shouldInjectListener,
        @Advice.Origin final Method method,
        @Advice.Return final OperationFuture future) {
      if (shouldInjectListener) {
        future.addListener(
            new DDTracingOperationCompletionListener(GlobalTracer.get(), method.getName()));
        CallDepthThreadLocalMap.reset(MemcachedClient.class);
      }
    }
  }

  public static class AsyncGetAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Boolean methodEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final Boolean shouldInjectListener,
        @Advice.Origin final Method method,
        @Advice.Return final GetFuture future) {
      if (shouldInjectListener) {
        future.addListener(
            new DDTracingGetCompletionListener(GlobalTracer.get(), method.getName()));
        CallDepthThreadLocalMap.reset(MemcachedClient.class);
      }
    }
  }

  public static class AsyncBulkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Boolean methodEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final Boolean shouldInjectListener,
        @Advice.Origin final Method method,
        @Advice.Return final BulkFuture future) {
      if (shouldInjectListener) {
        future.addListener(
            new DDTracingBulkCompletionListener(GlobalTracer.get(), method.getName()));
        CallDepthThreadLocalMap.reset(MemcachedClient.class);
      }
    }
  }
}
