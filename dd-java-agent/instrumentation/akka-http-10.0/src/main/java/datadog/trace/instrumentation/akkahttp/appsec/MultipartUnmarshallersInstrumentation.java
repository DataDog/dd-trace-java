package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.ScalaTraitMatchers.isTraitMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import akka.http.scaladsl.unmarshalling.MultipartUnmarshallers;
import akka.http.scaladsl.unmarshalling.Unmarshaller;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

/** @see MultipartUnmarshallers */
@AutoService(Instrumenter.class)
public class MultipartUnmarshallersInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForKnownTypes {

  private static final String TRAIT_NAME =
      "akka.http.scaladsl.unmarshalling.MultipartUnmarshallers";

  public MultipartUnmarshallersInstrumentation() {
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
                "multipartFormDataUnmarshaller",
                "akka.event.LoggingAdapter",
                "akka.http.scaladsl.settings.ParserSettings")
            .and(returns(named("akka.http.scaladsl.unmarshalling.Unmarshaller"))),
        MultipartUnmarshallersInstrumentation.class.getName() + "$UnmarshallerWrappingAdvice");
  }

  static class UnmarshallerWrappingAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) Unmarshaller unmarshaller) {
      unmarshaller = UnmarshallerHelpers.transformMultipartFormDataUnmarshaller(unmarshaller);
    }
  }
}
