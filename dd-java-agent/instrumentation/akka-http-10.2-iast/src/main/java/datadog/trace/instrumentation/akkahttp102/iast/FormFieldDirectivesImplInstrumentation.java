package datadog.trace.instrumentation.akkahttp102.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class FormFieldDirectivesImplInstrumentation extends ParameterDirectivesImplInstrumentation {
  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.directives.FormFieldDirectives$Impl$";
  }
}
