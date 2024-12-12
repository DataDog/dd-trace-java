package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class AbstractStringReaderInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes {

  public AbstractStringReaderInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fromString").and(isPublic().and(takesArguments(String.class))),
        getClass().getName() + "$FromStringAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.glassfish.jersey.internal.inject.ParamConverters$AbstractStringReader",
      "org.glassfish.jersey.server.internal.inject.ParamConverters$AbstractStringReader"
    };
  }

  public static class FromStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Argument(0) final String param, @Advice.Return Object result) {
      if (!(result instanceof String)) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final TaintedObjects to = IastContext.Provider.taintedObjects();
      module.taintObjectIfTainted(to, result, param);
    }
  }
}
