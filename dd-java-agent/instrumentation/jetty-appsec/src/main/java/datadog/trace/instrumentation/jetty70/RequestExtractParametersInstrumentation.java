package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.server.Request;

@AutoService(Instrumenter.class)
public class RequestExtractParametersInstrumentation extends Instrumenter.AppSec {
  public RequestExtractParametersInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.server.Request");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("extractParameters").or(named("getParameters")).and(takesArguments(0)),
        getClass().getName() + "$ExtractParametersAdvice");
  }

  public static class ExtractParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Request.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Enter final int depth) {
      if (depth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Request.class);
    }
  }
}
