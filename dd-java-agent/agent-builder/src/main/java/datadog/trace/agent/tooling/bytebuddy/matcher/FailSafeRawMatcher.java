package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import datadog.trace.util.HashingUtils;

/** {@link AgentBuilder.RawMatcher} that logs and swallows exceptions while matching. */
public class FailSafeRawMatcher implements AgentBuilder.RawMatcher {
  private static final Logger log = LoggerFactory.getLogger(FailSafeRawMatcher.class);

  /** The type matcher that might throw an exception. */
  private final ElementMatcher<? super TypeDescription> typeMatcher;

  /** The classloader matcher that might throw an exception. */
  private final ElementMatcher<? super ClassLoader> classLoaderMatcher;

  /** The text description to log if exception happens. */
  private final String description;

  /**
   * Creates a new fail-safe raw matcher.
   *
   * @param typeMatcher The type matcher that might throw an exception.
   * @param classLoaderMatcher The classloader matcher that might throw an exception.
   * @param description Descriptive string to log along with exception.
   */
  public FailSafeRawMatcher(
      ElementMatcher<? super TypeDescription> typeMatcher,
      ElementMatcher<? super ClassLoader> classLoaderMatcher,
      String description) {
    this.typeMatcher = typeMatcher;
    this.classLoaderMatcher = classLoaderMatcher;
    this.description = description;
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    try {
      return classLoaderMatcher.matches(classLoader) && typeMatcher.matches(typeDescription);
    } catch (Exception e) {
      log.debug(description, e);
      return false;
    }
  }

  @Override
  public String toString() {
    return "failSafe(try(" + typeMatcher + "," + classLoaderMatcher + ") or false)";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o instanceof FailSafeRawMatcher) {
      FailSafeRawMatcher that = (FailSafeRawMatcher) o;
      return typeMatcher.equals(that.typeMatcher)
          && classLoaderMatcher.equals(that.classLoaderMatcher);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(typeMatcher, classLoaderMatcher);
  }
}
