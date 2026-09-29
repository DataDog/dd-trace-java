package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments the real driver's {@link io.r2dbc.spi.Connection#createStatement(String)} to inject
 * DBM SQL comments before the statement reaches the database. This is the R2DBC equivalent of
 * JDBC's {@code DBMCompatibleConnectionInstrumentation}, which advises the real driver connection's
 * {@code prepareStatement}.
 *
 * <p>Hooking the SPI interface (via {@code ForTypeHierarchy} + {@code implementsInterface}) matches
 * every conforming driver's concrete connection with a single module, and — crucially — targets the
 * REAL driver connection rather than r2dbc-proxy's callback handlers. That keeps DBM injection off
 * the proxy internals and avoids the inject-vs-transform conflict.
 *
 * <p>Connection metadata (service, db type, host, db name) is resolved from the {@code
 * Connection}-to-{@code ConnectionFactoryOptions} {@link datadog.trace.bootstrap.ContextStore},
 * populated when the connection is created via the proxy metadata listener installed by {@link
 * R2dbcInstrumentation}.
 */
@AutoService(InstrumenterModule.class)
public class R2dbcConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public R2dbcConnectionInstrumentation() {
    super("r2dbc");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.Connection";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.r2dbc.spi.Connection", "io.r2dbc.spi.ConnectionFactoryOptions");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("createStatement"))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        getClass().getName() + "$CreateStatementAdvice");
  }

  public static class CreateStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This final Connection connection,
        @Advice.Argument(value = 0, readOnly = false) String sql) {
      ContextStore<Connection, ConnectionFactoryOptions> connectionOptionsStore =
          InstrumentationContext.get(Connection.class, ConnectionFactoryOptions.class);
      sql = R2dbcSqlCommentInjector.injectForConnection(sql, connection, connectionOptionsStore);
    }
  }
}
