package datadog.trace.instrumentation.tomcat;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.tomcat.websocket.server.WsHandshakeRequest;

@AutoService(InstrumenterModule.class)
public class WsHttpUpgradeHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public WsHttpUpgradeHandlerInstrumentation() {
    super("tomcat", "tomcat-websocket", "websocket");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.tomcat.websocket.server.WsHttpUpgradeHandler";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.apache.tomcat.websocket.server.WsHandshakeRequest", AgentSpan.class.getName());
  }

  @Override
  public String muzzleDirective() {
    return "tomcat-websocket";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("init"), getClass().getName() + "$CaptureHandshakeSpanAdvice");
  }

  public static class CaptureHandshakeSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.FieldValue("handshakeRequest") final WsHandshakeRequest request) {
      final AgentSpan span =
          InstrumentationContext.get(WsHandshakeRequest.class, AgentSpan.class).get(request);
      return span != null ? activateSpan(span) : null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
