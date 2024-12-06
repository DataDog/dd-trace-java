package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jersey.JerseyTaintHelper.taintMultiValuedMap;
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
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class FormInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes {

  public FormInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("asMap").and(isPublic()).and(takesArguments(0)),
        FormInstrumentation.class.getName() + "$AsMapAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"jakarta.ws.rs.core.Form", "javax.ws.rs.core.Form"};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JerseyTaintHelper",
    };
  }

  public static class AsMapAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PARAMETER_VALUE)
    public static void onExit(
        @Advice.Return Map<String, List<String>> form, @Advice.This Object self) {
      if (form == null || form.isEmpty()) {
        return;
      }
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module == null) {
        return;
      }
      final TaintedObjects to = IastContext.Provider.taintedObjects();
      if (!module.isTainted(to, self)) {
        return;
      }
      if (module.isTainted(to, form)) {
        return;
      }
      module.taintObject(to, form, SourceTypes.REQUEST_PARAMETER_VALUE);
      taintMultiValuedMap(to, module, SourceTypes.REQUEST_PARAMETER_VALUE, form);
    }
  }
}
