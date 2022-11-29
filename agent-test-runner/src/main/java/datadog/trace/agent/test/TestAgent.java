package datadog.trace.agent.test;

import static net.bytebuddy.description.modifier.FieldManifestation.VOLATILE;
import static net.bytebuddy.description.modifier.Ownership.STATIC;
import static net.bytebuddy.description.modifier.Visibility.PUBLIC;
import static net.bytebuddy.matcher.ElementMatchers.*;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.Installer;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.Transformer;

public class TestAgent {

  private static final String INST_CONFIG = "datadog.trace.api.InstrumenterConfig";
  private static final String CONFIG = "datadog.trace.api.Config";

  public static void premain(final String agentArgs, final Instrumentation inst) {
    new AgentBuilder.Default()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        // Config is injected into the bootstrap, so we need to provide a locator.
        .with(
            new AgentBuilder.LocationStrategy.Simple(
                ClassFileLocator.ForClassLoader.ofSystemLoader()))

        // Retransform in bootstrap classloader
        .ignore(none())
        // Transforms the Config class to make its INSTANCE field non-final and volatile.
        .type(namedOneOf(INST_CONFIG, CONFIG))
        .transform(
            (builder, typeDescription, classLoader, module, pd) ->
                builder
                    .field(named("INSTANCE"))
                    .transform(Transformer.ForField.withModifiers(PUBLIC, STATIC, VOLATILE)))
        .installOn(inst);
    Installer.premain(agentArgs, inst);
  }
}
