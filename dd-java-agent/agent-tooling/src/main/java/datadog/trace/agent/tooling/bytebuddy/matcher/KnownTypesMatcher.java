package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Fast type matcher that matches types against a set of known names. */
public class KnownTypesMatcher extends ElementMatcher.Junction.ForNonNullValues<TypeDescription>
    implements AgentBuilder.RawMatcher {

  private final Set<String> names;

  public KnownTypesMatcher(final String[] names) {
    this.names = new HashSet<>(names.length);
    Collections.addAll(this.names, names);
  }

  @Override
  protected boolean doMatch(TypeDescription target) {
    return names.contains(target.getName());
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    return doMatch(typeDescription);
  }

  /** Narrows this fast type matcher with the given classloader matcher. */
  public AgentBuilder.RawMatcher with(
      final ElementMatcher<? super ClassLoader> classLoaderMatcher) {
    return new AgentBuilder.RawMatcher() {
      @Override
      public boolean matches(
          TypeDescription typeDescription,
          ClassLoader classLoader,
          JavaModule module,
          Class<?> classBeingRedefined,
          ProtectionDomain protectionDomain) {
        return doMatch(typeDescription) && classLoaderMatcher.matches(classLoader);
      }
    };
  }
}
