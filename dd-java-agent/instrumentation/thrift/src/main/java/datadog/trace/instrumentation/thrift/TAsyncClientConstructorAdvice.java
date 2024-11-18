package datadog.trace.instrumentation.thrift;

import net.bytebuddy.asm.Advice;
import org.apache.thrift.TServiceClient;
import org.apache.thrift.async.TAsyncClient;
import org.apache.thrift.async.TAsyncMethodCall;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServer;

public class TAsyncClientConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.This TAsyncClient tAsyncClient
      , @Advice.FieldValue("___protocolFactory") TProtocolFactory ___protocolFactory
  ) throws NoSuchFieldException, IllegalAccessException {
//    TProtocolFactory inputProtocolFactory = (TProtocolFactory) ThriftConstants.getValue(
//        TAsyncClient.class,
//        tAsyncClient,
//        "___protocolFactory"
//    );

    ThriftConstants.setValue(
        TAsyncClient.class,
        tAsyncClient,
        "___protocolFactory",
        new CTProtocolFactory(___protocolFactory)
    );
  }
}
