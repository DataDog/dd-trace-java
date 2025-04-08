package datadog.trace.instrumentation.jetty9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class WebSocketSessionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public WebSocketSessionInstrumentation() {
    super("jetty", "jetty-websocket", "websocket");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.websocket.common.WebSocketSession";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "javax.websocket.Session",
        "datadog.trace.bootstrap.instrumentation.websocket.HandlerContext$Sender");
  }

  @Override
  public String muzzleDirective() {
    return "jetty-websocket";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.eclipse.jetty.websocket.jsr356.JsrSession");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isWebsocketTracingEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("close").and(takesNoArguments()), getClass().getName() + "$CloseAdvice");
  }

  public static class CloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final Object session,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {

      // that class is not implementing javax.websocket.Session but hides the close() method
      // hence we need an ad hoc advice
      handlerContext =
          (HandlerContext.Sender)
              InstrumentationContext.get(
                      "javax.websocket.Session",
                      "datadog.trace.bootstrap.instrumentation.websocket.HandlerContext$Sender")
                  .remove(session);
      if (handlerContext == null) {
        return null;
      }
      return activateSpan(DECORATE.onSessionCloseIssued(handlerContext, null, 1000));
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable thrown,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      if (scope != null) {
        DECORATE.onError(scope, thrown);
        DECORATE.onFrameEnd(handlerContext);
        scope.close();
      }
    }
  }
}
