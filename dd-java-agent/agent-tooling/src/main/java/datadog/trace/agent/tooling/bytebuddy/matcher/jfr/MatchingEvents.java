package datadog.trace.agent.tooling.bytebuddy.matcher.jfr;

import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.api.Platform;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatchingEvents {

  public static MatchingEvents get() {
    return INSTANCE;
  }

  public <T> ElementMatcher<T> matcherWithEvents(
      ElementMatcher<T> matcher, String instrumenterClass) {
    return matcher;
  }

  public MatchingEvent namedMatchingEvent(String name, int mode, Object data) {
    return MatchingEvent.NOOP;
  }

  public MatchingEvent hasInterfaceMatchingEvent(
      TypeDefinition typeDefinition, ElementMatcher<?> matcher) {
    return MatchingEvent.NOOP;
  }

  public MatchingEvent hasSuperMethodMatchingEvent(
      MethodDescription methodDescription, ElementMatcher<?> matcher) {
    return MatchingEvent.NOOP;
  }

  public MatchingEvent hasSuperTypeMatchingEvent(
      TypeDefinition typeDefinition, ElementMatcher<?> matcher) {
    return MatchingEvent.NOOP;
  }

  private static final Logger log = LoggerFactory.getLogger(MatchingEvents.class);
  private static final MatchingEvents INSTANCE = loadMatchingJfrEvents();

  private static MatchingEvents loadMatchingJfrEvents() {
    // check JFR support
    if (Platform.isJavaVersionAtLeast(8, 0, 262)) {
      try {
        return (MatchingEvents)
            AgentInstaller.class
                .getClassLoader()
                .loadClass("datadog.trace.agent.tooling.bytebuddy.matcher.MatchingEventsImpl")
                .getField("INSTANCE")
                .get(null);
      } catch (Throwable e) {
        log.warn(
            "Problem loading Java 8 JFR events support, falling back to no-op implementation.");
      }
    }
    return new MatchingEvents();
  }
}
