package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class R2dbcInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public R2dbcInstrumentation() {
    super("r2dbc");
  }

  @Override
  public String instrumentedType() {
    return "io.r2dbc.spi.ConnectionFactories";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      // r2dbc-proxy is bundled (implementation, not compileOnly — see build.gradle) but its
      // classes must ALSO be listed here: ordinary R2DBC apps don't depend on r2dbc-proxy, so
      // their classloader can't resolve it. helperClassNames() injects these classes directly
      // into the target app's classloader alongside our own, satisfying the runtime muzzle
      // safety check AND avoiding NoClassDefFoundError at actual call time — r2dbc-proxy's
      // internal call graph (ProxyConnectionFactory.builder() -> ProxyConfig -> callback/*
      // -> core/*, util/*) reaches far more classes than the handful directly imported by
      // our own helper classes, so the full non-optional class set is listed here rather
      // than a hand-picked subset (io.r2dbc.proxy.observation.* is excluded: it's an
      // optional Micrometer-Observation integration this module never uses, and requiring
      // it would add an undeclared io.micrometer dependency).
      //
      // ORDER MATTERS: helpers are injected in list order via ClassLoader.defineClass(),
      // which requires a type's supertypes/superinterfaces to already be defined on that
      // classloader. This is a genuine topological sort of the class's extends/implements
      // graph (computed from javap output, not just grouped by package/alphabetized —
      // alphabetizing within a package breaks e.g. ValueStore-before-DefaultValueStore).
      "io.r2dbc.proxy.callback.AfterQueryCallbackInvoker",
      "io.r2dbc.proxy.callback.CallbackHandler",
      "io.r2dbc.proxy.callback.CallbackHandlerSupport",
      "io.r2dbc.proxy.callback.BatchCallbackHandler",
      "io.r2dbc.proxy.callback.CallbackHandlerSupport$MethodInvocationStrategy",
      "io.r2dbc.proxy.callback.ConnectionCallbackHandler",
      "io.r2dbc.proxy.callback.ConnectionFactoryCallbackHandler",
      "io.r2dbc.proxy.callback.MethodInvocationSubscriber",
      "io.r2dbc.proxy.callback.ConnectionFactoryCreateMethodInvocationSubscriber",
      "io.r2dbc.proxy.callback.ConnectionHolder",
      "io.r2dbc.proxy.callback.ConnectionIdManager",
      "io.r2dbc.proxy.callback.DefaultConnectionIdManager",
      "io.r2dbc.proxy.core.ConnectionInfo",
      "io.r2dbc.proxy.callback.DefaultConnectionInfo",
      "io.r2dbc.proxy.callback.DelegatingContextView",
      "io.r2dbc.proxy.callback.ProxyFactory",
      "io.r2dbc.proxy.callback.JdkProxyFactory",
      "io.r2dbc.proxy.callback.JdkProxyFactory$CallbackInvocationHandler",
      "io.r2dbc.proxy.callback.ProxyFactoryFactory",
      "io.r2dbc.proxy.callback.JdkProxyFactoryFactory",
      "io.r2dbc.proxy.core.BindInfo",
      "io.r2dbc.proxy.callback.MutableBindInfo",
      "io.r2dbc.proxy.core.MethodExecutionInfo",
      "io.r2dbc.proxy.callback.MutableMethodExecutionInfo",
      "io.r2dbc.proxy.core.QueryExecutionInfo",
      "io.r2dbc.proxy.callback.MutableQueryExecutionInfo",
      "io.r2dbc.proxy.core.StatementInfo",
      "io.r2dbc.proxy.callback.MutableStatementInfo",
      "io.r2dbc.proxy.callback.ProxyConfig",
      "io.r2dbc.proxy.callback.ProxyConfig$1",
      "io.r2dbc.proxy.callback.ProxyConfig$Builder",
      "io.r2dbc.proxy.callback.ProxyConfigHolder",
      "io.r2dbc.proxy.callback.ProxyUtils",
      "io.r2dbc.proxy.callback.QueriesExecutionContext",
      "io.r2dbc.proxy.callback.QueryInvocationSubscriber",
      "io.r2dbc.proxy.callback.ResultCallbackHandler",
      "io.r2dbc.proxy.callback.ResultInvocationSubscriber",
      "io.r2dbc.proxy.callback.RowCallbackHandler",
      "io.r2dbc.proxy.callback.StatementCallbackHandler",
      "io.r2dbc.proxy.callback.StopWatch",
      "io.r2dbc.proxy.core.Binding",
      "io.r2dbc.proxy.core.Bindings",
      "io.r2dbc.proxy.core.Bindings$1",
      "io.r2dbc.proxy.core.Bindings$IndexBinding",
      "io.r2dbc.proxy.core.Bindings$NamedBinding",
      "io.r2dbc.proxy.core.BoundValue",
      "io.r2dbc.proxy.core.BoundValue$DefaultBoundValue",
      "io.r2dbc.proxy.core.ValueStore",
      "io.r2dbc.proxy.core.DefaultValueStore",
      "io.r2dbc.proxy.core.ExecutionType",
      "io.r2dbc.proxy.core.ProxyEventType",
      "io.r2dbc.proxy.core.QueryInfo",
      "io.r2dbc.proxy.core.R2dbcProxyException",
      "io.r2dbc.proxy.listener.BindParameterConverter",
      "io.r2dbc.proxy.listener.BindParameterConverter$1",
      "io.r2dbc.proxy.listener.BindParameterConverter$BindOperation",
      "io.r2dbc.proxy.listener.ProxyExecutionListener",
      "io.r2dbc.proxy.listener.CompositeProxyExecutionListener",
      "io.r2dbc.proxy.listener.LastExecutionAwareListener",
      "io.r2dbc.proxy.listener.ProxyMethodExecutionListener",
      "io.r2dbc.proxy.listener.ProxyMethodExecutionListenerAdapter",
      "io.r2dbc.proxy.listener.ResultRowConverter",
      "io.r2dbc.proxy.listener.ResultRowConverter$GetOperation",
      "io.r2dbc.proxy.ProxyConnectionFactory",
      "io.r2dbc.proxy.ProxyConnectionFactory$1",
      "io.r2dbc.proxy.ProxyConnectionFactory$Builder",
      "io.r2dbc.proxy.ProxyConnectionFactory$Builder$1",
      "io.r2dbc.proxy.ProxyConnectionFactory$Builder$2",
      "io.r2dbc.proxy.ProxyConnectionFactory$Builder$3",
      "io.r2dbc.proxy.ProxyConnectionFactory$Builder$4",
      "io.r2dbc.proxy.ProxyConnectionFactory$Builder$5",
      "io.r2dbc.proxy.ProxyConnectionFactoryProvider",
      "io.r2dbc.proxy.support.FormatterUtils",
      "io.r2dbc.proxy.support.MethodExecutionInfoFormatter",
      "io.r2dbc.proxy.support.QueryExecutionInfoFormatter",
      "io.r2dbc.proxy.util.Assert",
      packageName + ".R2dbcDecorator",
      packageName + ".R2dbcSqlCommentInjector",
      packageName + ".R2dbcTracingSupport",
      packageName + ".R2dbcTracingSupport$ConnectionMetadataListener",
      packageName + ".TraceProxyExecutionListener",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("find"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.r2dbc.spi.ConnectionFactoryOptions"))),
        getClass().getName() + "$ConnectionFactoriesAdvice");
  }

  public static class ConnectionFactoriesAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) ConnectionFactory factory,
        @Advice.Argument(0) ConnectionFactoryOptions options) {
      if (factory != null) {
        factory = R2dbcTracingSupport.wrapConnectionFactory(factory, options);
      }
    }
  }
}
