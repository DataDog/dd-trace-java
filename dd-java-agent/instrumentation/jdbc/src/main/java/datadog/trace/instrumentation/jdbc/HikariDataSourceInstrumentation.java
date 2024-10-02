package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.HikariDataSource;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public final class HikariDataSourceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

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
    return singletonMap("java.sql.Connection", DBInfo.class.getName());
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
      System.out.println("HELLO IM IN HERE");

      String hikariPoolname = ds.getPoolName();
      // ContextStore< Connection.class, DBInfo.class> store =
      // InstrumentationContext.get(Connection.class, DBInfo.class);
      // DBInfo dbInfo = store.get()

      System.out.println(ds.toString());
      System.out.println(hikariPoolname);
      System.out.println(con.toString());
      // System.out.println("Connection is " + hikariDSConnection.toString());

      DBInfo dbInfo = InstrumentationContext.get(Connection.class, DBInfo.class).get(unwrapped);
      if (dbInfo == null) {
        return;
      }
      System.out.println("after get dbinfo");
      dbInfo.setPoolName(hikariPoolname);
      System.out.println("after set in dbinfo");

      // System.out.println(dbInfo.getHikariPoolName() != null ? dbInfo.getHikariPoolName() : "it
      // was null");
      if (dbInfo.getPoolName() == null) {
        System.out.println("null");
      } else {
        System.out.println("dbinfo was set:");
        System.out.println(dbInfo.getPoolName());
      }

      // InstrumentationContext.get(Connection.class, DBInfo.class).put(hikariDSConnection,
      // dbInfo);
      // check if need to put

      // DECORATE.beforeFinish(scope.span());
      System.out.println("hello we eneded");
      System.out.println();
    }
  }
}
