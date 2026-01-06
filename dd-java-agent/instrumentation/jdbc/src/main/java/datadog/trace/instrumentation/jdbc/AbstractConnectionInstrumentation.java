package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logQueryInfoInjection;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public abstract class AbstractConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.HasMethodAdvice {
  public AbstractConnectionInstrumentation(String instrumentationName, String... additionalNames) {
    super(instrumentationName, additionalNames);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.Statement", DBQueryInfo.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.core.propagation.W3CTraceParent", packageName + ".JDBCDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a subtype of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        AbstractConnectionInstrumentation.class.getName() + "$ConnectionPrepareAdvice");
  }

  public static class ConnectionPrepareAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.This Connection connection,
        @Advice.Argument(0) final String sql,
        @Advice.Return final PreparedStatement statement) {
      ContextStore<Statement, DBQueryInfo> contextStore =
          InstrumentationContext.get(Statement.class, DBQueryInfo.class);
      if (null == contextStore.get(statement)) {
        DBQueryInfo info = DBQueryInfo.ofPreparedStatement(sql);
        contextStore.put(statement, info);
        logQueryInfoInjection(connection, statement, info);
      }
    }
  }
}
