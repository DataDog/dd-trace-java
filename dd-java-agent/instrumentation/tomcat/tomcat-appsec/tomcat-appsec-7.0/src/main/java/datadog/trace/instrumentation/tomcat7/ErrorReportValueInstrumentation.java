package datadog.trace.instrumentation.tomcat7;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class ErrorReportValueInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ErrorReportValueInstrumentation() {
    super("tomcat");
  }

  @Override
  public String muzzleDirective() {
    return "from7";
  }

  @Override
  public String instrumentedType() {
    return "org.apache.catalina.valves.ErrorReportValve";
  }

  @Override
  protected boolean isOptOutEnabled() {
    return true;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("report"))
            .and(takesArgument(0, named("org.apache.catalina.connector.Request")))
            .and(takesArgument(1, named("org.apache.catalina.connector.Response")))
            .and(takesArgument(2, Throwable.class))
            .and(isProtected()),
        packageName + ".ErrorReportValueAdvice");
  }
}
