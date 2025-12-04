package datadog.trace.instrumentation.play25.appsec;

import akka.util.ByteString;
import datadog.appsec.api.blocking.BlockingException;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import play.mvc.Http;

/**
 * @see play.mvc.BodyParser.FormUrlEncoded#parse(Http.RequestHeader, ByteString)
 */
public class BodyParserFormUrlEncodedParseAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Return Map<String, String[]> ret, @Advice.Thrown(readOnly = false) Throwable t) {
    if (t != null) {
      return;
    }
    try {
      // error is reported as client error, which doesn't preserve the exception
      BodyParserHelpers.handleArbitraryPostDataWithSpanError(ret, "FormUrlEncoded#parse");
    } catch (BlockingException be) {
      t = be;
    }
  }
}
