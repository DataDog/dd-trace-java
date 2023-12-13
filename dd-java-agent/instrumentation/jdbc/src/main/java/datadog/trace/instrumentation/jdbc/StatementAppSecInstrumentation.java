package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jdbc.DataDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.DataDecorator.logSQLException;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.sf.jsqlparser.JSQLParserException;

@AutoService(Instrumenter.class)
public class StatementAppSecInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForTypeHierarchy {

  public StatementAppSecInstrumentation() {
    super("jdbc-appsec");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.sql.Statement"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator",
      packageName + ".ResultSetReadWrapper",
      packageName + ".DataDecorator",
      packageName + ".SqlDataExtractor",
      packageName + ".SqlDataExtractor$ValueVisitor"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        nameStartsWith("executeQuery")
            .and(takesArguments(String.class))
            .and(returns(hasInterface(named("java.sql.ResultSet")))),
        StatementAppSecInstrumentation.class.getName() + "$StatementExecuteQueryAdvice");

    transformation.applyAdvice(
        nameStartsWith("executeUpdate").and(takesArguments(String.class).and(returns(int.class))),
        StatementAppSecInstrumentation.class.getName() + "$StatementExecuteUpdateAdvice");

    transformation.applyAdvice(
        nameStartsWith("executeQuery")
            .and(takesArguments(0))
            .and(isPublic())
            .and(returns(hasInterface(named("java.sql.ResultSet")))),
        StatementAppSecInstrumentation.class.getName() + "$PreparedStatementExecuteQueryAdvice");
  }

  public static class StatementExecuteQueryAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onBeforeQuery(
        @Advice.Argument(value = 0, readOnly = false) String sql,
        @Advice.This final Statement statement) {
      // TODO consider matching known non-wrapper implementations to avoid this check
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ResultSetReadWrapper.class);
      if (callDepth > 0) {
        return false;
      }
      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onQueryResult(
        @Advice.Enter final boolean input,
        @Advice.Argument(value = 0, readOnly = false) String sql,
        @Advice.Return(readOnly = false) ResultSet result,
        @Advice.Thrown final Throwable throwable) {
      if (!input || result == null) {
        return;
      }

      try {
        result = DECORATE.onResultSet(result);
      } catch (SQLException e) {
        logSQLException(e);
      }

      CallDepthThreadLocalMap.reset(ResultSetReadWrapper.class);
    }
  }

  public static class StatementExecuteUpdateAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onQueryResult(
        @Advice.Argument(value = 0, readOnly = false) String sql,
        @Advice.Return(readOnly = false) int result,
        @Advice.Thrown final Throwable throwable) {
      try {
        DECORATE.onUpdateResult(sql, result);
      } catch (JSQLParserException e) {
        logSQLException(e);
      }
    }
  }

  public static class PreparedStatementExecuteQueryAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onQueryResult(
        @Advice.Return(readOnly = false) ResultSet result,
        @Advice.Thrown final Throwable throwable) {
      try {
        result = DECORATE.onResultSet(result);
      } catch (SQLException e) {
        logSQLException(e);
      }
    }
  }
}
