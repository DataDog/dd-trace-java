package datadog.trace.instrumentation.tomcat;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.tomcat.websocket.server.WsHandshakeRequest;

@AutoService(InstrumenterModule.class)
public class WsHandshakeRequestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public WsHandshakeRequestInstrumentation() {
    super("tomcat", "tomcat-websocket", "websocket");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.tomcat.websocket.server.WsHandshakeRequest";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(instrumentedType(), AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureHandshakeSpanAdvice");
  }

  @Override
  public String muzzleDirective() {
    return "tomcat-websocket";
  }

  public static class CaptureHandshakeSpanAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void captureHandshakeSpan(@Advice.This final WsHandshakeRequest self) {
      final AgentSpan span = activeSpan();
      if (span != null) {
        // apply jee configuration overrides if any since the servlet instrumentation won't kick in
        // for this span.
        ClassloaderConfigurationOverrides.maybeEnrichSpan(span);
        InstrumentationContext.get(WsHandshakeRequest.class, AgentSpan.class)
            .putIfAbsent(self, span);
      }
    }
  }
}
