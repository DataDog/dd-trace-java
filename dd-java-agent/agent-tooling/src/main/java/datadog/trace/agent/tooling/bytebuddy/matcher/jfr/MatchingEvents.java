package datadog.trace.agent.tooling.bytebuddy.matcher.jfr;

import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.api.Platform;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatchingEvents {

  private static boolean ENABLED = false;

  public static MatchingEvents get() {
    return INSTANCE;
  }

  public <T> ElementMatcher<T> matcherWithEvents(
      ElementMatcher<T> matcher, String instrumenterClass) {
    return matcher;
  }

  public AgentBuilder.RawMatcher rawMatcherWithEvents(
      AgentBuilder.RawMatcher matcher, String instrumenterClass) {
    return matcher;
  }

  public AgentBuilder.RawMatcher rawMatcherWithEvents(
      AgentBuilder.RawMatcher matcher, Class<?> instrumenterClass) {
    return matcher;
  }

  private static final Logger log = LoggerFactory.getLogger(MatchingEvents.class);
  private static final MatchingEvents INSTANCE =
      ENABLED ? loadMatchingJfrEvents() : new MatchingEvents();

  private static MatchingEvents loadMatchingJfrEvents() {
    // check JFR support
    if (Platform.isJavaVersionAtLeast(8, 0, 262)) {
      boolean hasJfrSupport = false;
      try {
        AgentInstaller.class.getClassLoader().loadClass("jdk.jfr.Event");
        hasJfrSupport = true;
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }
      if (hasJfrSupport) {
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
    }
    return new MatchingEvents();
  }
}
