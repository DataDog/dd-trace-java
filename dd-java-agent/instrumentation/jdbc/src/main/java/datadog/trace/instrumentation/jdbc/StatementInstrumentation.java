package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.JDBCUtils.connectionFromStatement;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class StatementInstrumentation extends Instrumenter.Default {

  public StatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("java.sql.Statement"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCMaps", packageName + ".JDBCUtils", packageName + ".JDBCDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        nameStartsWith("execute").and(takesArgument(0, String.class)).and(isPublic()),
        StatementInstrumentation.class.getName() + "$StatementAdvice");
  }

  public static class StatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(0) final String sql, @Advice.This final Statement statement) {
      final Connection connection = connectionFromStatement(statement);
      if (connection == null) {
        return null;
      }

      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Statement.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan("database.query");
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, connection);
      DECORATE.onStatement(span, sql);
      span.setTag("span.origin.type", statement.getClass().getName());
      return activateSpan(span, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATE.onError(scope.span(), throwable);
      DECORATE.beforeFinish(scope.span());
      scope.close();
      CallDepthThreadLocalMap.reset(Statement.class);
    }
  }
}
