package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.HikariDataSource;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionContext;
import java.sql.Connection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class HikariDataSourceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(HikariDataSourceInstrumentation.class);

  public HikariDataSourceInstrumentation() {
    super("jdbc-datasource");
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.HikariDataSource";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.sql.Connection", JDBCConnectionContext.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getConnection"),
        HikariDataSourceInstrumentation.class.getName() + "$HikariGetConnectionAdvice");
  }

  public static class HikariGetConnectionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void start(
        @Advice.This final HikariDataSource ds, @Advice.Return Connection con) {
      if (con == null) {
        // Exception was probably thrown.
        return;
      }
      // connection pools wraps connection with their own type (ProxyConnection in this case)
      // jdbc drivers have a standard way to ask for the unwrapped instance (calling unwrap).
      // we need the unwrapped version in order to be able to lookup the instrumentation context
      // since we stored dbInfo for that instance and not for the wrapped one
      Connection unwrapped = con;
      try {
        if (con.isWrapperFor(Connection.class)) {
          unwrapped = con.unwrap(Connection.class);
        }
      } catch (Throwable t) {
        return;
      }
      String hikariPoolname = ds.getPoolName();
      if (unwrapped == null) {
        return;
      }
      JDBCConnectionContext connectionContext =
          InstrumentationContext.get(Connection.class, JDBCConnectionContext.class).get(unwrapped);
      if (connectionContext == null) {
        return;
      }
      connectionContext.setPoolName(hikariPoolname);
    }
  }
}
