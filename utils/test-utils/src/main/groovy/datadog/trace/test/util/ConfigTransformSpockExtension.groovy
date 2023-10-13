package datadog.trace.test.util

import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.Transformer
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.model.SpecInfo

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE
import static net.bytebuddy.description.modifier.Ownership.STATIC
import static net.bytebuddy.description.modifier.Visibility.PUBLIC
import static net.bytebuddy.matcher.ElementMatchers.named
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf
import static net.bytebuddy.matcher.ElementMatchers.none

/**
 * Transforms the Config class to make its INSTANCE field non-final and volatile.
 */
class ConfigTransformSpockExtension implements IGlobalExtension {
  static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig"
  static final String CONFIG = "datadog.trace.api.Config"

  @Override
  void start() {
    try {
      installConfigTransformer()
    } catch (IllegalStateException e) {
      /* Ignore. When we have -javaagent:dd-java-agent.jar, this is fine. */
    }
  }

  @Override
  void visitSpec(SpecInfo spec) {
  }

  @Override
  void stop() {
  }

  private void installConfigTransformer() {
    final instrumentation = ByteBuddyAgent.install()
    new AgentBuilder.Default()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
      // Config is injected into the bootstrap, so we need to provide a locator.
      .with(
      new AgentBuilder.LocationStrategy.Simple(
      ClassFileLocator.ForClassLoader.ofSystemLoader()))
      .ignore(none()) // Allow transforming bootstrap classes
      .type(namedOneOf(INST_CONFIG, CONFIG))
      .transform { builder, typeDescription, classLoader, module, pd ->
        builder
          .field(named("INSTANCE"))
          .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE))
      }
      .with(new ConfigInstrumentationFailedListener())
      .installOn(instrumentation)
  }
}
