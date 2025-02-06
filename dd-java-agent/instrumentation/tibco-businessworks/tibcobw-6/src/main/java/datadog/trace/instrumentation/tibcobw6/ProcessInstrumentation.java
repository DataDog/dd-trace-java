package datadog.trace.instrumentation.tibcobw6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import com.tibco.pvm.api.PmProcessInstance;
import com.tibco.pvm.api.PmWorkUnit;
import com.tibco.pvm.api.session.PmContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ProcessInstrumentation extends AbstractTibcoInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.tibco.pvm.system.manager.PmProcessInstanceManager";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("createInstance")), getClass().getName() + "$CreateInstanceAdvice");
  }

  public static class CreateInstanceAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(value = 0) PmContext pmContext, @Advice.Return PmProcessInstance process) {
      ContextStore<PmWorkUnit, AgentSpan> contextStore =
          InstrumentationContext.get(PmWorkUnit.class, AgentSpan.class);
      final PmProcessInstance parent = process.getParentProcess(pmContext);
      final AgentSpan parentSpan = parent != null ? contextStore.get(parent) : null;
      String appName = null;
      // we try to infer the service name using the tibco application name if it has not be set by
      // the user explicitly
      if (!Config.get().isServiceNameSetByUser()) {
        try {
          appName =
              (String)
                  process
                      .getModule(pmContext)
                      .getPrototype(pmContext)
                      .getAttributeValue(pmContext, "$bx_applicationName");
        } catch (Throwable t) {
          // cannot find the name
        }
      }
      try (AgentScope maybeScope = parentSpan != null ? activateSpan(parentSpan) : null) {
        AgentSpan span = startSpan(TibcoDecorator.TIBCO_PROCESS_OPERATION);
        TibcoDecorator.DECORATE.afterStart(span);
        if (appName != null) {
          AgentSpan root = span.getLocalRootSpan();
          if (root != null) {
            root.setServiceName(appName);
          }
          span.setServiceName(appName);
        }
        TibcoDecorator.DECORATE.onProcessStart(span, process.getName(pmContext));
        contextStore.put(process, span);
      }
    }
  }
}
