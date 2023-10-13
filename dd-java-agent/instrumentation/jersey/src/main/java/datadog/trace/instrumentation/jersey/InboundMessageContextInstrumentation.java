package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.source.WebModule;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class InboundMessageContextInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public InboundMessageContextInstrumentation() {
    super("jersey");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getHeaders").and(isPublic()).and(takesArguments(0)),
        InboundMessageContextInstrumentation.class.getName() + "$InstrumenterAdviceGetHeaders");

    transformation.applyAdvice(
        named("getRequestCookies").and(isPublic()).and(takesArguments(0)),
        InboundMessageContextInstrumentation.class.getName()
            + "$InstrumenterAdviceGetRequestCookies");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.InboundMessageContext";
  }

  public static class InstrumenterAdviceGetHeaders {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit(@Advice.Return Map<String, List<String>> headers) {
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      final WebModule web = InstrumentationBridge.WEB;
      if (prop != null && web != null) {
        web.onHeaderNames(headers.keySet());
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
          for (String value : entry.getValue()) {
            prop.taint(SourceTypes.REQUEST_HEADER_VALUE, entry.getKey(), value);
          }
        }
      }
    }
  }

  public static class InstrumenterAdviceGetRequestCookies {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_COOKIE_VALUE)
    public static void onExit(@Advice.Return Map<String, Object> cookies) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        for (Object cookie : cookies.values()) {
          module.taintObject(SourceTypes.REQUEST_COOKIE_VALUE, cookie);
        }
      }
    }
  }
}
