package datadog.trace.instrumentation.jackson.codehouse.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.sink.SsrfModule;
import java.io.InputStream;
import java.io.Reader;
import java.net.URL;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class Json1FactoryInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public Json1FactoryInstrumentation() {
    super("jackson", "jackson-1");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("createJsonParser")
            .and(isMethod())
            .and(
                takesArguments(String.class)
                    .or(takesArguments(InputStream.class))
                    .or(takesArguments(Reader.class))
                    .or(takesArguments(URL.class))
                    .or(takesArguments(byte[].class))),
        Json1FactoryInstrumentation.class.getName() + "$InstrumenterAdvice");
    transformer.applyAdvice(
        named("createJsonParser")
            .and(isMethod())
            .and(isPublic().and(takesArguments(byte[].class, int.class, int.class))),
        Json1FactoryInstrumentation.class.getName() + "$Instrumenter2Advice");
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
          propagation.taintObjectIfTainted(parser, input);
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

  public static class Instrumenter2Advice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(
        @Advice.Argument(0) final byte[] input,
        @Advice.Argument(1) final int offset,
        @Advice.Argument(2) final int length,
        @Advice.Return final Object parser) {
      if (input != null || length <= 0) {
        final PropagationModule propagation = InstrumentationBridge.PROPAGATION;
        if (propagation != null) {
          propagation.taintObjectIfRangeTainted(parser, input, offset, length, false, NOT_MARKED);
        }
      }
    }
  }
}
