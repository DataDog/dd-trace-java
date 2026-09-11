package datadog.trace.instrumentation.r2dbc;

import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import net.bytebuddy.asm.Advice;

public class ConnectionCreateBatchAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterCreateBatch(
      @Advice.This Connection connection, @Advice.Return Batch batch) {
    if (batch != null) {
      R2dbcConnectionInfo info =
          InstrumentationContext.get(Connection.class, R2dbcConnectionInfo.class).get(connection);
      if (info != null) {
        InstrumentationContext.get(Batch.class, R2dbcConnectionInfo.class).put(batch, info);
      }
    }
  }
}
