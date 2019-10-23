package datadog.trace.instrumentation.jsp;

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jsp.JSPDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.jasper.JspCompilationContext;

@AutoService(Instrumenter.class)
public final class JasperJSPCompilationContextInstrumentation extends Instrumenter.Default {

  public JasperJSPCompilationContextInstrumentation() {
    super("jsp", "jsp-compile");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.apache.jasper.JspCompilationContext");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.decorator.BaseDecorator", packageName + ".JSPDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("compile").and(takesArguments(0)).and(isPublic()),
        JasperJspCompilationContext.class.getName());
  }

  public static class JasperJspCompilationContext {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter() {
      final AgentSpan span = startSpan("jsp.compile");
      DECORATE.afterStart(span);
      return activateSpan(span, true);
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
    }
  }
}
