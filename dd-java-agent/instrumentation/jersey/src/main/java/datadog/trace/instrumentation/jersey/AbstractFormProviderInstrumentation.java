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
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AbstractFormProviderInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public AbstractFormProviderInstrumentation() {
    super("jersey");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("readFrom").and(isPublic()).and(takesArguments(4)),
        AbstractFormProviderInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.AbstractFormProvider";
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(@Advice.Return Map<String, List<String>> result) {
      final PropagationModule prop = InstrumentationBridge.PROPAGATION;
      if (prop == null || result == null || result.isEmpty()) {
        return;
      }
      for (Map.Entry<String, List<String>> entry : result.entrySet()) {
        final String name = entry.getKey();
        prop.taint(name, SourceTypes.REQUEST_PARAMETER_NAME, name);
        for (String value : entry.getValue()) {
          prop.taint(value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
        }
      }
    }
  }
}
