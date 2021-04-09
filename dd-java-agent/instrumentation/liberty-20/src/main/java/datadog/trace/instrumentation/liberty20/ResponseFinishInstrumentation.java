package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletResponse;
import com.ibm.wsspi.webcontainer.servlet.IExtendedRequest;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ResponseFinishInstrumentation extends Instrumenter.Tracing {

  public ResponseFinishInstrumentation() {
    super("liberty");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LibertyDecorator", packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("com.ibm.ws.webcontainer.srt.SRTServletResponse");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("finish").and(takesNoArguments()),
        ResponseFinishInstrumentation.class.getName() + "$ResponseFinishAdvice");
  }

  public static class ResponseFinishAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(@Advice.This SRTServletResponse resp) {
      IExtendedRequest req = resp.getRequest();
      Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);

      if (spanObj instanceof AgentSpan) {
        req.setAttribute(DD_SPAN_ATTRIBUTE, null);
        final AgentSpan span = (AgentSpan) spanObj;
        DECORATE.onResponse(span, resp);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
