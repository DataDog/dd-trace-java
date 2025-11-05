package datadog.trace.instrumentation.springweb6;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.springframework.web.servlet.ModelAndView;

public class RenderAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(@Advice.Argument(0) final ModelAndView mv) {
    final AgentSpan span = startSpan(SpringWebHttpServerDecorator.RESPONSE_RENDER);
    SpringWebHttpServerDecorator.DECORATE_RENDER.afterStart(span);
    SpringWebHttpServerDecorator.DECORATE_RENDER.onRender(span, mv);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    SpringWebHttpServerDecorator.DECORATE_RENDER.onError(scope, throwable);
    SpringWebHttpServerDecorator.DECORATE_RENDER.beforeFinish(scope);
    scope.close();
    scope.span().finish();
  }
}
