package datadog.trace.instrumentation.jackson.core;

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import java.io.Reader;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;

@AutoService(Instrumenter.class)
public class Json2FactoryInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public Json2FactoryInstrumentation() {
    super("jackson", "jackson-2");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {

    transformation.applyAdvice(
        NameMatchers.<MethodDescription>named("createParser")
            .and(isMethod())
            .and(
                isPublic()
                    .and(takesArguments(String.class).or(takesArguments(InputStream.class)))
                    .or(takesArguments(Reader.class))),
        Json2FactoryInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.core.JsonFactory";
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final Object input, @Advice.Return final Object parser) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfInputIsTainted(parser, input);
      }
    }
  }
}
