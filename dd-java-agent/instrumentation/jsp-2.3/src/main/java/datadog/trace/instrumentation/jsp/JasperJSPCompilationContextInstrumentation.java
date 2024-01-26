package datadog.trace.instrumentation.jsp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jsp.JSPDecorator.DECORATE;
import static datadog.trace.instrumentation.jsp.JSPDecorator.JSP_COMPILE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.jasper.JspCompilationContext;

@AutoService(Instrumenter.class)
public final class JasperJSPCompilationContextInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public JasperJSPCompilationContextInstrumentation() {
    super("jsp", "jsp-compile");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.jasper.JspCompilationContext";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JSPDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("compile").and(takesArguments(0)).and(isPublic()),
        JasperJSPCompilationContextInstrumentation.class.getName()
            + "$JasperJspCompilationContext");
  }

  public static class JasperJspCompilationContext {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter() {
      final AgentSpan span = startSpan(JSP_COMPILE);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final JspCompilationContext jspCompilationContext,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      DECORATE.onCompile(scope, jspCompilationContext);
      // ^ Decorate on return because additional properties are available

      DECORATE.onError(scope, throwable);
      DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
