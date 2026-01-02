package datadog.trace.instrumentation.akkahttp.appsec;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class AkkaHttpAppSecModule extends InstrumenterModule.AppSec {

  public AkkaHttpAppSecModule() {
    super("akka-http");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AkkaBlockResponseFunction",
      packageName + ".BlockingResponseHelper",
      packageName + ".MarkSpanAsErroredPF",
      packageName + ".ScalaListCollector",
      packageName + ".UnmarshallerHelpers",
      packageName + ".UnmarshallerHelpers$UnmarkStrictFormOngoingOnUnsupportedException",
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
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new Bug4304Instrumentation(),
        new ConfigProvideRemoteAddressHeaderInstrumentation(),
        new DefaultExceptionHandlerInstrumentation(),
        new FormDataToStrictInstrumentation(),
        new JacksonUnmarshallerInstrumentation(),
        new MultipartUnmarshallersInstrumentation(),
        new PredefinedFromEntityUnmarshallersInstrumentation(),
        new SprayUnmarshallerInstrumentation(),
        new StrictFormCompanionInstrumentation());
  }
}
