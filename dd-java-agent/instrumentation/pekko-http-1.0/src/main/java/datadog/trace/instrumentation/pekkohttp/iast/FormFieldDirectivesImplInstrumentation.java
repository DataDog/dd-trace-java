package datadog.trace.instrumentation.pekkohttp.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class FormFieldDirectivesImplInstrumentation extends ParameterDirectivesImplInstrumentation {
  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.server.directives.FormFieldDirectives$Impl$";
  }
}
