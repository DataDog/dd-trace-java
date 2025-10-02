package datadog.trace.instrumentation.pekkohttp.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class FormFieldDirectivesImplInstrumentation extends ParameterDirectivesImplInstrumentation {
  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.server.directives.FormFieldDirectives$Impl$";
  }
}
