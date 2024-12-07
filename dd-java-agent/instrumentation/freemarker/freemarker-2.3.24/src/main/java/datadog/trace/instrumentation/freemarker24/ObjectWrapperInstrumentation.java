package datadog.trace.instrumentation.freemarker24;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import freemarker.template.TemplateModel;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ObjectWrapperInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy {

  public ObjectWrapperInstrumentation() {
    super("freemarker");
  }

  @Override
  public String hierarchyMarkerType() {
    return "freemarker.template.ObjectWrapper";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("wrap")
            .and(takesArgument(0, named("java.lang.Object")))
            .and(returns(named("freemarker.template.TemplateModel"))),
        getClass().getName() + "$ObjectWrapperAdvice");
  }

  public static class ObjectWrapperAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void onExit(
        @Advice.Return final TemplateModel templateModel, @Advice.Argument(0) final Object object) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        final TaintedObjects to = IastContext.Provider.taintedObjects();
        module.taintObjectIfTainted(to, templateModel, object);
      }
    }
  }
}
