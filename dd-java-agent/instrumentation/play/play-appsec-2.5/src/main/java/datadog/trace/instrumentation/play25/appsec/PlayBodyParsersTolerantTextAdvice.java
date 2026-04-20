package datadog.trace.instrumentation.play25.appsec;

import net.bytebuddy.asm.Advice;
import play.api.mvc.BodyParser;
import play.core.Execution;

public class PlayBodyParsersTolerantTextAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.Return(readOnly = false) BodyParser<String> parser) {
    parser =
        parser.map(
            BodyParserHelpers.getHandleStringMapF(),
            Execution.Implicits$.MODULE$.internalContext());
  }
}
