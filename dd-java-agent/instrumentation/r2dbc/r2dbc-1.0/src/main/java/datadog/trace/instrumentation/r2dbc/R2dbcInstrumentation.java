package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import net.bytebuddy.asm.Advice;

public class R2dbcInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.r2dbc.spi.ConnectionFactories";
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
        ContextStore<Connection, ConnectionFactoryOptions> connectionOptionsStore =
            InstrumentationContext.get(Connection.class, ConnectionFactoryOptions.class);
        factory =
            R2dbcTracingSupport.wrapConnectionFactory(factory, options, connectionOptionsStore);
      }
    }
  }
}
