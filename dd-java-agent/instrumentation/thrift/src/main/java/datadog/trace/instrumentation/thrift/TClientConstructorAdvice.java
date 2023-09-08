package datadog.trace.instrumentation.thrift;

import net.bytebuddy.asm.Advice;
import org.apache.thrift.TServiceClient;
import org.apache.thrift.protocol.TProtocol;

public class TClientConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.This TServiceClient tServiceClient
      , @Advice.FieldValue("oprot_") TProtocol oprot_
  ) throws NoSuchFieldException, IllegalAccessException {
    if (!(oprot_ instanceof ClientOutProtocolWrapper)) {
      ThriftConstants.setValue(
          TServiceClient.class,
          tServiceClient,
          "oprot_",
          new ClientOutProtocolWrapper(oprot_)
      );
    }
  }
}
