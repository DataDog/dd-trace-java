package datadog.trace.instrumentation.struts1;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import javax.servlet.ServletException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.struts.action.ExceptionHandler;

@AutoService(Instrumenter.class)
public final class ExceptionHandlerInstrumentation extends Instrumenter.Tracing {

  public ExceptionHandlerInstrumentation() {
    super("struts-1");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.apache.struts.action.ExceptionHandler");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return extendsClass(named("org.apache.struts.action.ExceptionHandler"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("execute").and(takesArgument(0, Exception.class)),
        ExceptionHandlerInstrumentation.class.getName() + "$ExceptionHandlerAdvice");
  }

  public static class ExceptionHandlerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean handle(@Advice.Argument(0) Exception exception) {
      AgentSpan span = activeSpan();
      if (span != null) {
        int depth = CallDepthThreadLocalMap.incrementCallDepth(ExceptionHandler.class);
        if (depth == 0) {
          span.setError(true);
          span.addThrowable(exception);
          return true;
        }
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void reset(@Advice.Enter boolean reset) {
      if (reset) {
        CallDepthThreadLocalMap.reset(ExceptionHandler.class);
      }
    }

    private static void muzzleCheck(ExceptionHandler exceptionHandler) throws ServletException {
      exceptionHandler.execute(new Exception(), null, null, null, null, null);
    }
  }
}
