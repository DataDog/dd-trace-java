package datadog.trace.instrumentation.play25.appsec;

import com.fasterxml.jackson.databind.JsonNode;
import datadog.appsec.api.blocking.BlockingException;
import net.bytebuddy.asm.Advice;

public class BodyParserTolerantJsonParseAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(@Advice.Return JsonNode ret, @Advice.Thrown(readOnly = false) Throwable t) {
    if (t != null) {
      return;
    }
    try {
      BodyParserHelpers.handleJsonNode(ret, "TolerantJson#parse");
    } catch (BlockingException be) {
      t = be;
    }
  }
}
