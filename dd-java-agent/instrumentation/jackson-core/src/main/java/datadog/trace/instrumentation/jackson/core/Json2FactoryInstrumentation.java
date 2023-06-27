package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.SsrfModule;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class Json2FactoryInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public Json2FactoryInstrumentation() {
    super("jackson", "jackson-2");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("createParser")
            .and(isMethod())
            .and(
                isPublic()
                    .and(
                        takesArguments(String.class)
                            .or(takesArguments(InputStream.class))
                            .or(takesArguments(Reader.class))
                            .or(takesArguments(URL.class)))),
        Json2FactoryInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.core.JsonFactory";
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.SSRF) // it's both propagation and Sink but Sink takes priority
    public static void onExit(
        @Advice.Argument(0) final Object input, @Advice.Return final Object parser) {
      if (input != null) {
        final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
        if (propagation != null) {
          propagation.taintIfInputIsTainted(parser, input);
        }
        if (input instanceof URL) {
          final SsrfModule ssrf = InstrumentationBridge.SSRF;
          if (ssrf != null) {
            ssrf.onURLConnection(input);
          }
        }
      }
    }
  }
}
