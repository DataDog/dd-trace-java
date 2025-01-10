package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ScalaTraitMatchers.isTraitMethod;
import static datadog.trace.instrumentation.play26.appsec.NoDeclaredMethodMatcher.hasNoDeclaredMethod;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import play.api.libs.json.JsValue;
import play.api.mvc.BodyParser;
import play.api.mvc.MultipartFormData;
import play.api.mvc.PlayBodyParsers;
import play.core.Execution;
import scala.collection.Seq;
import scala.collection.immutable.Map;
import scala.xml.NodeSeq;

/** @see play.api.mvc.PlayBodyParsers$class#tolerantFormUrlEncoded(PlayBodyParsers, int) */
@AutoService(InstrumenterModule.class)
public class PlayBodyParsersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  private static final String TRAIT_NAME = "play.api.mvc.PlayBodyParsers";

  public PlayBodyParsersInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {TRAIT_NAME, TRAIT_NAME + "$class"};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BodyParserHelpers",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "tolerantFormUrlEncoded", is(int.class).or(is(long.class)))
            .and(returns(named("play.api.mvc.BodyParser"))),
        PlayBodyParsersInstrumentation.class.getName() + "$UrlEncodedAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "tolerantText", long.class)
            .and(returns(named("play.api.mvc.BodyParser"))),
        PlayBodyParsersInstrumentation.class.getName() + "$TextAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "text", long.class)
            .and(returns(named("play.api.mvc.BodyParser"))),
        PlayBodyParsersInstrumentation.class.getName() + "$TextAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "multipartFormData", "scala.Function1", long.class, boolean.class)
            .and(returns(named("play.api.mvc.BodyParser"))),
        PlayBodyParsersInstrumentation.class.getName() + "$MultipartFormDataAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "multipartFormData", "scala.Function1", long.class)
            .and(returns(named("play.api.mvc.BodyParser")))
            .and(
                /* only if prev didn't match */
                hasNoDeclaredMethod(
                    isTraitMethod(
                            TRAIT_NAME,
                            "multipartFormData",
                            "scala.Function1",
                            long.class,
                            boolean.class)
                        .and(returns(named("play.api.mvc.BodyParser"))))),
        PlayBodyParsersInstrumentation.class.getName() + "$MultipartFormDataAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "tolerantJson", is(int.class).or(is(long.class)))
            .and(returns(named("play.api.mvc.BodyParser"))),
        PlayBodyParsersInstrumentation.class.getName() + "$JsonAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "tolerantXml", is(int.class).or(is(long.class)))
            .and(returns(named("play.api.mvc.BodyParser"))),
        PlayBodyParsersInstrumentation.class.getName() + "$XmlAdvice");
  }

  static class UrlEncodedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return(readOnly = false) BodyParser<Map<String, Seq<String>>> parser) {

      parser =
          parser.map(
              BodyParserHelpers.getHandleUrlEncodedMapF(),
              Execution.Implicits$.MODULE$.trampoline());
    }
  }

  static class TextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before() {
      CallDepthThreadLocalMap.incrementCallDepth(PlayBodyParsers.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return(readOnly = false) BodyParser<String> parser, @Advice.Thrown Throwable t) {
      int depth = CallDepthThreadLocalMap.decrementCallDepth(PlayBodyParsers.class);
      if (depth > 0 || t != null) {
        return;
      }

      parser =
          parser.map(
              BodyParserHelpers.getHandleStringMapF(), Execution.Implicits$.MODULE$.trampoline());
    }
  }

  static class MultipartFormDataAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) BodyParser<MultipartFormData<?>> parser) {

      parser =
          parser.map(
              BodyParserHelpers.getHandleMultipartFormDataF(),
              Execution.Implicits$.MODULE$.trampoline());
    }
  }

  static class JsonAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) BodyParser<JsValue> parser) {

      parser =
          parser.map(BodyParserHelpers.getHandleJsonF(), Execution.Implicits$.MODULE$.trampoline());
    }
  }

  static class XmlAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) BodyParser<NodeSeq> parser) {

      parser =
          parser.map(BodyParserHelpers.getHandleXmlF(), Execution.Implicits$.MODULE$.trampoline());
    }
  }
}
