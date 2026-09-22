package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.r2dbc.proxy.core.ConnectionInfo;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Instruments {@code io.r2dbc.proxy.callback.BatchCallbackHandler} to inject DBM SQL comments into
 * queries added to a {@link io.r2dbc.spi.Batch} before they reach the database driver. This mirrors
 * {@link R2dbcConnectionCallbackInstrumentation}'s handling of {@code createStatement}, but for the
 * {@code Batch#add(String)} path, which {@link R2dbcConnectionCallbackInstrumentation} does not see
 * (batches are a separate proxied object, not a method on the proxied {@code Connection}).
 */
@AutoService(InstrumenterModule.class)
public class R2dbcBatchCallbackInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public R2dbcBatchCallbackInstrumentation() {
    super("r2dbc");
  }

  @Override
  public String instrumentedType() {
    return "io.r2dbc.proxy.callback.BatchCallbackHandler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      // See R2dbcInstrumentation#helperClassNames for why the full r2dbc-proxy class set
      // (rather than a hand-picked subset) is required, and for the topological ordering
      // rationale (supertypes must be injected before their implementing classes).
      "io.r2dbc.proxy.callback.AfterQueryCallbackInvoker",
      "io.r2dbc.proxy.callback.CallbackHandler",
      "io.r2dbc.proxy.callback.CallbackHandlerSupport",
      // BatchCallbackHandler and ConnectionCallbackHandler are intentionally excluded from
      // ALL r2dbc helper lists: they are instrumentedType()s of this module and
      // R2dbcConnectionCallbackInstrumentation. A class that is helper-injected is loaded
      // untransformed, so listing an instrumented type as a helper (in any module) prevents
      // its advice from ever being applied.
      "io.r2dbc.proxy.callback.CallbackHandlerSupport$MethodInvocationStrategy",
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
            .and(named("invoke"))
            .and(takesArguments(3))
            .and(takesArgument(0, Object.class))
            .and(takesArgument(1, Method.class))
            .and(takesArgument(2, Object[].class)),
        getClass().getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(1) final Method method,
        @Advice.Argument(value = 2, readOnly = false) Object[] args,
        @Advice.FieldValue("connectionInfo") final ConnectionInfo connectionInfo) {
      if (args == null || args.length == 0) {
        return;
      }
      if (!"add".equals(method.getName())) {
        return;
      }
      if (!(args[0] instanceof String)) {
        return;
      }

      String sql = (String) args[0];
      String injected = R2dbcSqlCommentInjector.injectForConnection(sql, connectionInfo);
      if (!sql.equals(injected)) {
        // Replace the SQL argument with the injected version.
        // We must create a new array because ByteBuddy advice cannot mutate the original
        // array reference in place for @Advice.Argument(readOnly=false).
        Object[] newArgs = new Object[args.length];
        System.arraycopy(args, 0, newArgs, 0, args.length);
        newArgs[0] = injected;
        args = newArgs;
      }
    }
  }
}
