package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Fast type matcher that matches types against a single name. */
public class SingleTypeMatcher extends ElementMatcher.Junction.ForNonNullValues<TypeDescription>
    implements AgentBuilder.RawMatcher {

  private final String name;

  public SingleTypeMatcher(final String name) {
    this.name = name;
  }

  @Override
  protected boolean doMatch(TypeDescription target) {
    return name.equals(target.getName());
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
