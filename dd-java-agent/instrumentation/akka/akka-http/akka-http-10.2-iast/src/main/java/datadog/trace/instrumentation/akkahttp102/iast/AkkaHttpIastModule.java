package datadog.trace.instrumentation.akkahttp102.iast;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class AkkaHttpIastModule extends InstrumenterModule.Iast {
  public AkkaHttpIastModule() {
    super("akka-http");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".helpers.TaintParametersFunction",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    // just so we can use assertInverse in the muzzle directive
    return new Reference[] {
      new Reference.Builder("akka.http.scaladsl.server.directives.FormFieldDirectives$Impl$")
          .build(),
      new Reference.Builder("akka.http.scaladsl.server.directives.ParameterDirectives$Impl$")
          .build(),
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new FormFieldDirectivesImplInstrumentation(), new ParameterDirectivesImplInstrumentation());
  }
}
