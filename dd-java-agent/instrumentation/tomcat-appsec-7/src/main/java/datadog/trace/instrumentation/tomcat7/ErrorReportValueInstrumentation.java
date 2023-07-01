package datadog.trace.instrumentation.tomcat7;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public class ErrorReportValueInstrumentation extends Instrumenter.AppSec implements Instrumenter.ForSingleType {

    public ErrorReportValueInstrumentation() {
        super("tomcat");
    }

    @Override
    public String instrumentedType() {
        return "org.apache.catalina.valves.ErrorReportValve";
    }

    @Override
    public String[] helperClassNames() {
        return new String[] {
                "java.lang.StringBuilder",
                "java.io.Writer",
                "java.io.IOException",
                "java.lang.IllegalStateException",
                "datadog.trace.bootstrap.instrumentation.decorator.StacktraceLeakDecorator"
        };
    }

    @Override
    public void adviceTransformations(AdviceTransformation transformation) {
        transformation.applyAdvice(
                isMethod()
                        .and(named("report"))
                        .and(takesArgument(0, named("org.apache.catalina.connector.Request")))
                        .and(takesArgument(1, named("org.apache.catalina.connector.Response")))
                        .and(takesArgument(2, Throwable.class))
                        .and(isProtected()),
                packageName + ".ErrorReportValueAdvice");
    }
}
