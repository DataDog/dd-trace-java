package datadog.trace.instrumentation.r2dbc;

import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.reactivestreams.Publisher;

public class ConnectionFactoryCreateAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterCreate(
      @Advice.This ConnectionFactory factory,
      @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
          Publisher<? extends Connection> publisher) {
    if (publisher == null) {
      return;
    }

    R2dbcConnectionInfo info = R2dbcConnectionInfoExtractor.extract(factory);
    if (info != null) {
      publisher =
          new MetadataWrappingPublisher(
              publisher,
              info,
              InstrumentationContext.get(Connection.class, R2dbcConnectionInfo.class));
    }
  }
}
