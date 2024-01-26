package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Map;
import java.util.Properties;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class DriverInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public DriverInstrumentation() {
    super("jdbc");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("java.sql.Driver"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.Connection", DBInfo.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JDBCDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        nameStartsWith("connect")
            .and(takesArgument(0, String.class))
            .and(takesArgument(1, Properties.class))
            .and(returns(named("java.sql.Connection"))),
        DriverInstrumentation.class.getName() + "$DriverAdvice");
  }

  public static class DriverAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addDBInfo(
        @Advice.Argument(0) final String url,
        @Advice.Argument(1) final Properties props,
        @Advice.Return final Connection connection) {
      if (connection == null) {
        // Exception was probably thrown.
        return;
      }
      String connectionUrl = url;
      Properties connectionProps = props;
      try {
        DatabaseMetaData metaData = connection.getMetaData();
        connectionUrl = metaData.getURL();
        if (null != connectionUrl && !connectionUrl.equals(url)) {
          // connection url was updated, check to see if user has also changed
          String connectionUser = metaData.getUserName();
          if (null != connectionUser
              && (null == props || !connectionUser.equalsIgnoreCase(props.getProperty("user")))) {
            // merge updated user with original properties
            connectionProps = new Properties(props);
            connectionProps.put("user", connectionUser);
          }
        } else {
          connectionUrl = url; // fallback in case updated url is null
        }
      } catch (Throwable ignored) {
        // use original values
      }
      DBInfo dbInfo = JDBCConnectionUrlParser.extractDBInfo(connectionUrl, connectionProps);
      InstrumentationContext.get(Connection.class, DBInfo.class).put(connection, dbInfo);
    }
  }
}
