package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import net.bytebuddy.asm.Advice;
import play.core.j.JavaParsers$;
import play.mvc.BodyParser;
import play.mvc.Http;

/**
 * @see play.mvc.BodyParser.DelegatingBodyParser
 */
@AutoService(InstrumenterModule.class)
public class DelegatingBodyParserInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DelegatingBodyParserInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_26_PLUS;
  }

  @Override
  public String instrumentedType() {
    return "play.mvc.BodyParser$DelegatingBodyParser";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JavaMultipartFormDataRegisterExcF",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("play.mvc.Http$RequestHeader")))
            .and(returns(named("play.libs.streams.Accumulator"))),
        DelegatingBodyParserInstrumentation.class.getName() + "$ApplyAdvice");
  }

  static class ApplyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This BodyParser.DelegatingBodyParser thiz,
        @Advice.Return(readOnly = false) play.libs.streams.Accumulator ret) {
      if (!thiz.getClass().getName().equals("play.mvc.BodyParser$MultipartFormData")) {
        return;
      }
      play.libs.streams.Accumulator<
              akka.util.ByteString,
              play.libs.F.Either<
                  play.mvc.Result, Http.MultipartFormData<play.libs.Files.TemporaryFile>>>
          acc = ret;

      ret =
          acc.recover(
              JavaMultipartFormDataRegisterExcF.INSTANCE, JavaParsers$.MODULE$.trampoline());
    }
  }
}
