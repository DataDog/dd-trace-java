package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.bootstrap.instrumentation.jdbc.JDBCDecorator.PREPARED_STATEMENTS_SQL;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.sql.PreparedStatement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ConnectionInstrumentation extends Instrumenter.Tracing {

  public ConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return PreparedStatementInstrumentation.CLASS_LOADER_MATCHER;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.PreparedStatement", UTF8BytesString.class.getName());
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("java.sql.Connection"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        nameStartsWith("prepare")
            .and(takesArgument(0, String.class))
            // Also include CallableStatement, which is a sub type of PreparedStatement
            .and(returns(hasInterface(named("java.sql.PreparedStatement")))),
        ConnectionInstrumentation.class.getName() + "$ConnectionPrepareAdvice");
  }

  public static class ConnectionPrepareAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.Argument(0) final String sql, @Advice.Return final PreparedStatement statement) {
      ContextStore<PreparedStatement, UTF8BytesString> contextStore =
          InstrumentationContext.get(PreparedStatement.class, UTF8BytesString.class);
      if (null == contextStore.get(statement)) {
        // Sometimes the prepared statement is not reused, but the underlying String is reused, so
        // check if we have seen this String before
        UTF8BytesString utf8Sql = PREPARED_STATEMENTS_SQL.computeIfAbsent(sql, UTF8_ENCODE);
        contextStore.putIfAbsent(statement, utf8Sql);
      }
    }
  }
}
