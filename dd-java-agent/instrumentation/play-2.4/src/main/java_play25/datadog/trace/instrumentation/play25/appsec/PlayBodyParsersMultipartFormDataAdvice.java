package datadog.trace.instrumentation.play25.appsec;

import net.bytebuddy.asm.Advice;
import play.api.mvc.BodyParser;
import play.core.Execution;
import scala.Function1;

/** @see play.api.mvc.BodyParsers.parse$#multipartFormData(Function1, long) */
public class PlayBodyParsersMultipartFormDataAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.Return(readOnly = false) BodyParser<play.api.mvc.MultipartFormData<?>> parser) {
    parser =
        parser.map(
            BodyParserHelpers.getHandleMultipartFormDataF(),
            Execution.Implicits$.MODULE$.internalContext());
  }
}
