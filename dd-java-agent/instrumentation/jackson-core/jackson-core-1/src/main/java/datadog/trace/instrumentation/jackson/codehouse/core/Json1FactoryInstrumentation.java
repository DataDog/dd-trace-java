package datadog.trace.instrumentation.jackson.codehouse.core;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.SsrfModule;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;

@AutoService(Instrumenter.class)
public class Json1FactoryInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public Json1FactoryInstrumentation() {
    super("jackson", "jackson-1");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        NameMatchers.<MethodDescription>named("createJsonParser")
            .and(isMethod())
            .and(
                takesArguments(String.class)
                    .or(takesArguments(InputStream.class))
                    .or(takesArguments(Reader.class))
                    .or(takesArguments(URL.class))),
        Json1FactoryInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.codehaus.jackson.JsonFactory";
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.SSRF) // SSRF takes priority over the propagation one
    public static void onExit(
        @Advice.Argument(0) final Object input, @Advice.Return final Object parser) {
      if (input != null) {
        final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
        if (propagation != null) {
          propagation.taintIfTainted(parser, input);
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
