package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ScalaTraitMatchers.isTraitMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers;
import akka.http.scaladsl.unmarshalling.Unmarshaller;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import net.bytebuddy.asm.Advice;
import scala.collection.Seq;

/**
 * @see PredefinedFromEntityUnmarshallers#urlEncodedFormDataUnmarshaller(Seq)
 * @see PredefinedFromEntityUnmarshallers#stringUnmarshaller()
 */
@AutoService(InstrumenterModule.class)
public class PredefinedFromEntityUnmarshallersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String TRAIT_NAME =
      "akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers";

  public PredefinedFromEntityUnmarshallersInstrumentation() {
    super("akka-http");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".UnmarshallerHelpers",
      packageName + ".UnmarshallerHelpers$UnmarkStrictFormOngoingOnUnsupportedException",
      packageName + ".AkkaBlockResponseFunction",
      packageName + ".BlockingResponseHelper",
      packageName + ".ScalaListCollector",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders",
      "datadog.trace.instrumentation.akkahttp.UriAdapter",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return ScalaListCollectorMuzzleReferences.additionalMuzzleReferences();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      TRAIT_NAME, TRAIT_NAME + "$class",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isTraitMethod(
                TRAIT_NAME,
                "urlEncodedFormDataUnmarshaller",
                namedOneOf("scala.collection.Seq", "scala.collection.immutable.Seq"))
            .and(returns(named("akka.http.scaladsl.unmarshalling.Unmarshaller"))),
        PredefinedFromEntityUnmarshallersInstrumentation.class.getName()
            + "$UrlEncodedUnmarshallerWrappingAdvice");
    transformer.applyAdvice(
        isTraitMethod(TRAIT_NAME, "stringUnmarshaller")
            .and(returns(named("akka.http.scaladsl.unmarshalling.Unmarshaller"))),
        PredefinedFromEntityUnmarshallersInstrumentation.class.getName()
            + "$StringUnmarshallerWrappingAdvice");
  }

  static class UrlEncodedUnmarshallerWrappingAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) Unmarshaller unmarshaller) {
      unmarshaller = UnmarshallerHelpers.transformUrlEncodedUnmarshaller(unmarshaller);
    }
  }

  static class StringUnmarshallerWrappingAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.Return(readOnly = false)
            Unmarshaller<akka.http.scaladsl.model.HttpEntity, String> unmarshaller) {
      unmarshaller = UnmarshallerHelpers.transformStringUnmarshaller(unmarshaller);
    }
  }
}
