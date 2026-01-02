package datadog.trace.instrumentation.akkahttp.iast;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.akkahttp.appsec.FormDataToStrictInstrumentation;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class AkkaHttpIastModule extends InstrumenterModule.Iast {
  public AkkaHttpIastModule() {
    super("akka-http");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders",
      "datadog.trace.instrumentation.akkahttp.UriAdapter",
      "datadog.trace.instrumentation.akkahttp.appsec.UnmarshallerHelpers",
      "datadog.trace.instrumentation.akkahttp.appsec.AkkaBlockResponseFunction",
      "datadog.trace.instrumentation.akkahttp.appsec.UnmarshallerHelpers$UnmarkStrictFormOngoingOnUnsupportedException",
      "datadog.trace.instrumentation.akkahttp.appsec.BlockingResponseHelper",
      "datadog.trace.instrumentation.akkahttp.appsec.ScalaListCollector",
      packageName + ".helpers.ScalaToJava",
      packageName + ".helpers.TaintCookieFunction",
      packageName + ".helpers.TaintFutureHelper",
      packageName + ".helpers.TaintOptionalCookieFunction",
      packageName + ".helpers.TaintUriFunction",
      packageName + ".helpers.TaintRequestFunction",
      packageName + ".helpers.TaintRequestContextFunction",
      packageName + ".helpers.TaintMultiMapFunction",
      packageName + ".helpers.TaintMapFunction",
      packageName + ".helpers.TaintSeqFunction",
      packageName + ".helpers.TaintSingleParameterFunction",
      packageName + ".helpers.TaintUnmarshaller",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new CookieDirectivesInstrumentation(),
        new CookieHeaderInstrumentation(),
        new ExtractDirectivesInstrumentation(),
        new FormDataToStrictInstrumentation(),
        new FormFieldDirectivesInstrumentation(),
        new HttpHeaderSubclassesInstrumentation(),
        new HttpRequestInstrumentation(),
        new MakeTaintableInstrumentation(),
        new MarshallingDirectivesInstrumentation(),
        new ParameterDirectivesInstrumentation(),
        new PathMatcherInstrumentation(),
        new RequestContextInstrumentation(),
        new UnmarshallerInstrumentation(),
        new UriInstrumentation());
  }
}
