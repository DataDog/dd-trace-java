package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.logSQLException;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.HikariDataSource;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import java.sql.SQLException;
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getConnection"),
        HikariDataSourceInstrumentation.class.getName() + "$HikariGetConnectionAdvice");
  }

  public static class HikariGetConnectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void start(@Advice.This final HikariDataSource ds) {
      System.out.println("HELLO IM IN HERE");
      //      if (activeSpan() == null) {
      //        // Don't want to generate a new top-level span
      //        return;
      //      }
      // final AgentSpan span = activeSpan();
      String hikariPoolname = ds.getPoolName();
      // span.setTag("hikari.poolname", hikariPoolname);
      // ContextStore< Connection.class, DBInfo.class> store =
      // InstrumentationContext.get(Connection.class, DBInfo.class);
      // DBInfo dbInfo = store.get()x
      System.out.println(ds.toString());
      try {
        System.out.println("HELLO IM IN TRY");
        System.out.println(ds.toString());

        Connection hikariDSConnection = ds.getConnection();
        System.out.println("HELLO IM AFTER GETCONNECTION");
        System.out.println("Connection is " + hikariDSConnection.toString());
        DBInfo dbInfo =
            InstrumentationContext.get(Connection.class, DBInfo.class).get(hikariDSConnection);
        System.out.println("after");
        dbInfo.setHikariPoolName(hikariPoolname);
        // InstrumentationContext.get(Connection.class, DBInfo.class).put(hikariDSConnection,
        // dbInfo);
        // check if need to put
        System.out.println();
      } catch (SQLException e) {
        System.out.println("CAUGHT SQLEXCEPTION");
        logSQLException(e);
      } catch (Exception e) {
        System.out.println("CAUGHT EXCEPTION");
        log.error("e: ", e);
      }
      System.out.println("hello we eneded");
    }
  }
}
