package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.sql.PreparedStatement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ConnectionInstrumentation extends Instrumenter.Default {

  public ConnectionInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("java.sql.Connection"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCMaps",
    };
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
      JDBCMaps.preparedStatements.put(statement, sql);
    }
  }
}
