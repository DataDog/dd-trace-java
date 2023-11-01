package datadog.trace.instrumentation.play25.appsec;

import net.bytebuddy.asm.Advice;
import play.core.j.JavaParsers$;
import play.libs.streams.Accumulator;
import play.mvc.BodyParser;
import play.mvc.Http;

/** @see BodyParser.DelegatingBodyParser#apply(Http.RequestHeader) */
public class BodyParserDelegatingBodyParserApplyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This BodyParser thiz,
      @Advice.Return(readOnly = false) play.libs.streams.Accumulator ret) {
    if (!thiz.getClass().getName().equals("play.mvc.BodyParser$MultipartFormData")) {
      return;
    }
    Accumulator<
            akka.util.ByteString, play.libs.F.Either<play.mvc.Result, Http.MultipartFormData<?>>>
        acc = ret;

    ret =
        acc.recover(JavaMultipartFormDataRegisterExcF.INSTANCE, JavaParsers$.MODULE$.trampoline());
  }
}
