package datadog.trace.instrumentation.akkahttp102.iast;

public class FormFieldDirectivesImplInstrumentation extends ParameterDirectivesImplInstrumentation {
  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.directives.FormFieldDirectives$Impl$";
  }
}
