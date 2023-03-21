package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import java.io.Reader;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class JsonFactoryInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public JsonFactoryInstrumentation() {
    super("jackson-core");
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.core.JsonFactory";
  }

  @Override
  public void adviceTransformations(final AdviceTransformation transformation) {
    adviceTransformation(String.class, transformation);
    adviceTransformation(InputStream.class, transformation);
    adviceTransformation(Reader.class, transformation);
  }

  private void adviceTransformation(
      final Class<?> argument, final AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("createParser")
            .and(isMethod())
            .and(isPublic())
            .and(takesArguments(1))
            .and(takesArgument(0, argument)),
        JsonFactoryInstrumentation.class.getName() + "$CreateParserAdvice");
  }

  public static class CreateParserAdvice {
    @Advice.OnMethodExit
    public static void params(
        @Advice.Argument(0) final Object input, @Advice.Return final Object parser) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        try {
          module.taintIfInputIsTainted(parser, input);
        } catch (final Throwable e) {
          module.onUnexpectedException("afterCreate threw", e);
        }
      }
    }
  }
}
