package stackstate.trace.instrumentation.jboss;

import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.Utils;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.NameMatcher;
import net.bytebuddy.matcher.StringMatcher;

@AutoService(Instrumenter.class)
public final class JBossClassloadingInstrumentation extends Instrumenter.Default {
  public JBossClassloadingInstrumentation() {
    super("jboss-classloading");
  }

  @Override
  public ElementMatcher typeMatcher() {
    return new NameMatcher(
        new StringMatcher("org.jboss.modules.Module", StringMatcher.Mode.EQUALS_FULLY) {
          @Override
          public boolean matches(String target) {
            if (super.matches(target)) {
              // This instrumentation modifies no bytes.
              // Instead it sets a system prop to tell jboss to delegate
              // classloads for datadog bootstrap classes
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
            }
            return false;
          }
        });
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    return Collections.emptyMap();
  }
}
