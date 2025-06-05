package datadog.trace.instrumentation.vertx_sql_client_39;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import io.vertx.sqlclient.PreparedStatement;
import io.vertx.sqlclient.Query;
import net.bytebuddy.asm.Advice;

public class PreparedStatementQueryAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterQuery(
      @Advice.This final PreparedStatement zis, @Advice.Return final Query query) {
    InstrumentationContext.get(Query.class, Pair.class)
        .put(query, InstrumentationContext.get(PreparedStatement.class, Pair.class).get(zis));
  }
}
