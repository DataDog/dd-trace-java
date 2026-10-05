package datadog.trace.instrumentation.play25.appsec;

import datadog.appsec.api.blocking.BlockingException;
import net.bytebuddy.asm.Advice;

public class BodyParserTolerantTextParseAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  static void after(@Advice.Return String ret, @Advice.Thrown(readOnly = false) Throwable t) {
    if (t != null) {
      return;
    }
    try {
      // error is reported as client error, which doesn't preserve the exception
      BodyParserHelpers.handleArbitraryPostDataWithSpanError(ret, "TolerantText#parse");
    } catch (BlockingException be) {
      t = be;
    }
  }
}
