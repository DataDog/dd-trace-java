package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AbstractParamValueExtractorInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public AbstractParamValueExtractorInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fromString").and(isProtected().and(takesArguments(String.class))),
        AbstractParamValueExtractorInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ThreadLocalSourceType"};
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.internal.inject.AbstractParamValueExtractor";
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(
        @Advice.Return Object result, @Advice.FieldValue("parameterName") String parameterName) {
      if (result instanceof String) {
        final PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          module.taint(result, ThreadLocalSourceType.get(), parameterName);
        }
      }
    }
  }
}
