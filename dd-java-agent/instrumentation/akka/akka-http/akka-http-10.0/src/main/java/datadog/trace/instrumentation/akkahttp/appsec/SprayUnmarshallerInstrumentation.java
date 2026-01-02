package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ScalaTraitMatchers.isTraitMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import akka.http.scaladsl.unmarshalling.Unmarshaller;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

// TODO: move to separate module and have better support
public class SprayUnmarshallerInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String TRAIT_NAME =
      "akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport";

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      TRAIT_NAME, TRAIT_NAME + "$class",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "sprayJsonUnmarshaller", "spray.json.RootJsonReader")
            .and(returns(named("akka.http.scaladsl.unmarshalling.Unmarshaller")))
            .or(
                isTraitMethod(
                        TRAIT_NAME, "sprayJsonByteStringUnmarshaller", "spray.json.RootJsonReader")
                    .and(returns(named("akka.http.scaladsl.unmarshalling.Unmarshaller")))),
        SprayUnmarshallerInstrumentation.class.getName() + "$ArbitraryTypeAdvice");
    // support is basic:
    // * Source[T, NotUsed] is not intercepted
    // * neither is the conversion into JsValue. It would need to wrap the JsValue
    //   to intercept calls to the methods in play.api.libs.json.JsReadable
  }

  static class ArbitraryTypeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) Unmarshaller ret) {
      ret = UnmarshallerHelpers.transformArbitrarySprayUnmarshaller(ret);
    }
  }
}
