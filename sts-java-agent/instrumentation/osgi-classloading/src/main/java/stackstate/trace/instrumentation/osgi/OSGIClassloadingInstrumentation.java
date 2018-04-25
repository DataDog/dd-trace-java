package stackstate.trace.instrumentation.osgi;

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
public final class OSGIClassloadingInstrumentation extends Instrumenter.Configurable {
  public OSGIClassloadingInstrumentation() {
    super("osgi-classloading");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        // OSGI Bundle class loads the sys prop which defines bootstrap classes
        .type(named("org.osgi.framework.Bundle"))
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
                // Instead it sets a system prop to tell osgi to delegate
                // classloads for stackstate bootstrap classes
                StringBuilder stsPrefixes = new StringBuilder("");
                for (int i = 0; i < Utils.BOOTSTRAP_PACKAGE_PREFIXES.length; ++i) {
                  if (i > 0) {
                    // must append twice. Once for exact package and wildcard for child packages
                    stsPrefixes.append(",");
                  }
                  stsPrefixes.append(Utils.BOOTSTRAP_PACKAGE_PREFIXES[i]).append(".*,");
                  stsPrefixes.append(Utils.BOOTSTRAP_PACKAGE_PREFIXES[i]);
                }
                final String existing = System.getProperty("org.osgi.framework.bootdelegation");
                if (null == existing) {
                  System.setProperty("org.osgi.framework.bootdelegation", stsPrefixes.toString());
                } else if (!existing.contains(stsPrefixes)) {
                  System.setProperty(
                      "org.osgi.framework.bootdelegation", existing + "," + stsPrefixes.toString());
                }
                return builder;
              }
            })
        .asDecorator();
  }
}
