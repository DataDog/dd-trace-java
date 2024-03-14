package datadog.trace.instrumentation.akkahttp102.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class FormFieldDirectivesImplInstrumentation extends ParameterDirectivesImplInstrumentation {
  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.directives.FormFieldDirectives$Impl$";
  }
}
