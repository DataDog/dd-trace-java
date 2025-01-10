package datadog.trace.instrumentation.wildfly;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.jboss.as.ee.component.BindingConfiguration;
import org.jboss.as.ee.component.EnvEntryInjectionSource;

@AutoService(InstrumenterModule.class)
public class ResourceReferenceProcessorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ResourceReferenceProcessorInstrumentation() {
    super("wildfly", "jee-env-entry");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.as.ee.component.deployers.ResourceReferenceProcessor";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.jboss.as.ee.component.EnvEntryInjectionSource", Object.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getEnvironmentEntries")),
        getClass().getName() + "$GetEnvironmentEntriesAdvice");
  }

  public static class GetEnvironmentEntriesAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(value = 1) final ClassLoader classLoader,
        @Advice.Return final List<BindingConfiguration> configurations) {
      ClassloaderConfigurationOverrides.ContextualInfo info = null;
      ContextStore<EnvEntryInjectionSource, Object> contextStore =
          InstrumentationContext.get(EnvEntryInjectionSource.class, Object.class);
      for (BindingConfiguration bindingConfiguration : configurations) {
        if (bindingConfiguration.getSource() instanceof EnvEntryInjectionSource) {
          final Object value =
              contextStore.get((EnvEntryInjectionSource) bindingConfiguration.getSource());
          if (value != null
              && bindingConfiguration
                  .getName()
                  .startsWith(ClassloaderConfigurationOverrides.DATADOG_TAGS_JNDI_PREFIX)) {
            if (info == null) {
              info = ClassloaderConfigurationOverrides.maybeCreateContextualInfo(classLoader);
            }
            info.addTag(
                bindingConfiguration
                    .getName()
                    .substring(ClassloaderConfigurationOverrides.DATADOG_TAGS_JNDI_PREFIX.length()),
                value);
          }
        }
      }
    }
  }
}
