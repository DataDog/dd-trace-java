package datadog.trace.util.test

import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.Transformer
import spock.lang.Specification
import spock.util.environment.Jvm

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE
import static net.bytebuddy.description.modifier.Ownership.STATIC
import static net.bytebuddy.description.modifier.Visibility.PUBLIC
import static net.bytebuddy.matcher.ElementMatchers.named
import static net.bytebuddy.matcher.ElementMatchers.none

abstract class DDSpecification extends Specification {
  private static final String CONFIG = "datadog.trace.api.Config"

  static {
    makeConfigInstanceModifiable()
  }

  // Keep track of config instance already made modifiable
  private static isConfigInstanceModifiable = false


  // CircleCI will provide us with a container running along side our build.
  // When building locally however, we need to take matters into our own hands
  // and we use 'testcontainers' for this.
  static boolean shouldUseTestContainers() {
    return "true" != System.getenv("CI")
  }

  // Do not run tests locally on Java7 since testcontainers are not compatible with Java7
  // It is fine to run on non-Gitlab CIs because they provide rabbitmq externally, not through testcontainers
  static boolean containerTestCompatible() {
    return (Jvm.current.java8Compatible || "true" == System.getenv("CI")) && System.getenv("GITLAB_CI") == null
  }

  static void makeConfigInstanceModifiable() {
    if (isConfigInstanceModifiable) {
      return
    }

    def instrumentation = ByteBuddyAgent.install()
    new AgentBuilder.Default()
      .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
      .with(AgentBuilder.RedefinitionStrategy.Listener.ErrorEscalating.FAIL_FAST)
    // Config is injected into the bootstrap, so we need to provide a locator.
      .with(
        new AgentBuilder.LocationStrategy.Simple(
          ClassFileLocator.ForClassLoader.ofSystemLoader()))
      .ignore(none()) // Allow transforming bootstrap classes
      .type(named(CONFIG))
      .transform { builder, typeDescription, classLoader, module ->
        builder
          .field(named("INSTANCE"))
          .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE))
      }
    // Making runtimeId modifiable so that it can be preserved when resetting config in tests
      .transform { builder, typeDescription, classLoader, module ->
        builder
          .field(named("runtimeId"))
          .transform(Transformer.ForField.withModifiers(PUBLIC, VOLATILE))
      }
      .installOn(instrumentation)
    isConfigInstanceModifiable = true
  }
}
