package stackstate.trace.instrumentation.jboss;

import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSTransformers;
import stackstate.trace.agent.tooling.Utils;

@AutoService(Instrumenter.class)
public final class JBossClassloadingInstrumentation extends Instrumenter.Configurable {
  public JBossClassloadingInstrumentation() {
    super("jboss-classloading");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        // Jboss Module class loads the sys prop which defines bootstrap classes
        .type(named("org.jboss.modules.Module"))
        .transform(STSTransformers.defaultTransformers())
        .transform(
            new AgentBuilder.Transformer() {
              @Override
              public DynamicType.Builder<?> transform(
                  DynamicType.Builder<?> builder,
                  TypeDescription typeDescription,
                  ClassLoader classLoader,
                  JavaModule javaModule) {
                // This instrumentation modifies no bytes.
                // Instead it sets a system prop to tell jboss to delegate
                // classloads for stackstate bootstrap classes
                StringBuilder stsPrefixes = new StringBuilder("");
                for (int i = 0; i < Utils.BOOTSTRAP_PACKAGE_PREFIXES.length; ++i) {
                  if (i > 0) {
                    stsPrefixes.append(",");
                  }
                  stsPrefixes.append(Utils.BOOTSTRAP_PACKAGE_PREFIXES[i]);
                }
                final String existing = System.getProperty("jboss.modules.system.pkgs");
                if (null == existing) {
                  System.setProperty("jboss.modules.system.pkgs", stsPrefixes.toString());
                } else if (!existing.contains(stsPrefixes)) {
                  System.setProperty(
                      "jboss.modules.system.pkgs", existing + "," + stsPrefixes.toString());
                }
                return builder;
              }
            })
        .asDecorator();
  }
}
