package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

/**
 * Global ignores matcher used by the agent.
 *
 * <p>This matcher services two main purposes:
 *
 * <ul>
 *   <li>Ignore classes that are unsafe or pointless to transform. 'System' level classes like jvm
 *       classes or groovy classes, other tracers, debuggers, etc.
 *   <li>Ignore additional classes to minimize the number of classes we apply expensive matchers to.
 * </ul>
 */
public class GlobalIgnoresMatcher implements AgentBuilder.RawMatcher {

  public static GlobalIgnoresMatcher globalIgnoresMatcher(boolean skipAdditionalLibraryMatcher) {
    return new GlobalIgnoresMatcher(skipAdditionalLibraryMatcher);
  }

  private final boolean skipAdditionalLibraryMatcher;

  private GlobalIgnoresMatcher(final boolean skipAdditionalLibraryMatcher) {
    this.skipAdditionalLibraryMatcher = skipAdditionalLibraryMatcher;
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    if (ClassLoaderMatchers.skipClassLoader(classLoader)) {
      return true;
    }
    String name = typeDescription.getActualName();
    return GlobalIgnores.isIgnored(name, skipAdditionalLibraryMatcher)
        || CustomExcludes.isExcluded(name)
        || ProxyIgnores.isIgnored(name);
  }

  @Override
  public String toString() {
    return "globalIgnoresMatcher(" + skipAdditionalLibraryMatcher + ")";
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    } else if (other == null) {
      return false;
    } else if (getClass() != other.getClass()) {
      return false;
    } else {
      return skipAdditionalLibraryMatcher
          == ((GlobalIgnoresMatcher) other).skipAdditionalLibraryMatcher;
    }
  }

  @Override
  public int hashCode() {
    return 17 * 31 + (skipAdditionalLibraryMatcher ? 1231 : 1237);
  }
}
