package datadog.trace.instrumentation.play25.appsec;

import net.bytebuddy.asm.Advice;
import play.api.mvc.BodyParser;
import play.core.Execution;
import scala.collection.Seq;

public class PlayBodyParsersTolerantFormUrlEncodedAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.Return(readOnly = false)
          BodyParser<scala.collection.immutable.Map<String, Seq<String>>> parser) {
    parser =
        parser.map(
            BodyParserHelpers.getHandleUrlEncodedMapF(),
            Execution.Implicits$.MODULE$.internalContext());
  }
}
