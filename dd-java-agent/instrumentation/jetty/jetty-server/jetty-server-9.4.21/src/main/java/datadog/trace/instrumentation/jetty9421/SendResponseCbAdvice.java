package datadog.trace.instrumentation.jetty9421;

import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpTransport;
import org.eclipse.jetty.util.Callback;

public class SendResponseCbAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
  public static boolean /* skip */ before(
      @Advice.This HttpChannel connection,
      @Advice.Argument(0) MetaData.Response responseInfo,
      @Advice.Argument(3) Callback cb,
      @Advice.FieldValue("_transport") HttpTransport _transport) {
    return JettyCommitResponseHelper.before(connection, responseInfo, _transport, cb);
  }
}
