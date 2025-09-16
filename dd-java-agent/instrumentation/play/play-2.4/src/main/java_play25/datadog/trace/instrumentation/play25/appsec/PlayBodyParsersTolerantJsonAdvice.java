package datadog.trace.instrumentation.play25.appsec;

import net.bytebuddy.asm.Advice;
import play.api.libs.json.JsValue;
import play.api.mvc.BodyParser;
import play.core.Execution;

/** @see play.api.mvc.BodyParsers.parse$#tolerantJson(int) */
public class PlayBodyParsersTolerantJsonAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.Return(readOnly = false) BodyParser<JsValue> parser) {
    parser =
        parser.map(
            BodyParserHelpers.getHandleJsonF(), Execution.Implicits$.MODULE$.internalContext());
  }
}
