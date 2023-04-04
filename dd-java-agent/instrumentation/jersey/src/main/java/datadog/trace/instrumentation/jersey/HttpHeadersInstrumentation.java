package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class HttpHeadersInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForKnownTypes {

  public HttpHeadersInstrumentation() {
    super("jersey");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getHeaderString").and(isPublic().and(takesArguments(String.class))),
        HttpHeadersInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"javax.ws.rs.core.HttpHeaders", "jakarta.ws.rs.core.HttpHeaders"};
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodExit
    public static void onExit(
        @Advice.Argument(0) String headerName, @Advice.Return(readOnly = true) String headerValue) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.namedTaint(SourceTypes.REQUEST_HEADER_VALUE, headerName, headerValue);
        } catch (final Throwable e) {
          module.onUnexpectedException("HttpHeadersInstrumentation threw", e);
        }
      }
    }
  }
}
