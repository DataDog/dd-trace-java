package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.MatchingEvent;
import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.MatchingEvents;
import java.security.ProtectionDomain;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Instantiated by reflection when current JDK supports JFR */
public class MatchingEventsImpl extends MatchingEvents {

  public static MatchingEvents INSTANCE = new MatchingEventsImpl();

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.Matching")
  @Label("Matching")
  static class MatchingEventImpl extends Event implements MatchingEvent {
    @Label("Instrumentation")
    String instrumentation;

    @Label("Matcher")
    String matcher;

    @Label("Target")
    String target;

    @Label("Matched")
    boolean matched;

    @Override
    public void close() {
      if (shouldCommit()) {
        commit();
      }
    }
  }

  private static class MatcherWrapper<T> implements ElementMatcher<T> {
    final ElementMatcher<T> matcher;
    final String instrumenterClass;

    private MatcherWrapper(ElementMatcher<T> matcher, String instrumenterClass) {
      this.matcher = matcher;
      this.instrumenterClass = instrumenterClass;
    }

    @Override
    public boolean matches(T target) {
      try (MatchingEventImpl event = newMatchingEvent(target)) {
        boolean matched = matcher.matches(target);
        event.matched = matched;
        return matched;
      }
    }

    private MatchingEventImpl newMatchingEvent(T target) {
      MatchingEventImpl event = new MatchingEventImpl();
      if (event.isEnabled()) {
        event.instrumentation = instrumenterClass;
        event.matcher = matcher.toString();
        event.target = target.toString();

        event.begin();
      }
      return event;
    }
  }

  private static class RawMatcherWrapper implements AgentBuilder.RawMatcher {
    final AgentBuilder.RawMatcher matcher;
    final String instrumenterClass;

    private RawMatcherWrapper(AgentBuilder.RawMatcher matcher, String instrumenterClass) {
      this.matcher = matcher;
      this.instrumenterClass = instrumenterClass;
    }

    @Override
    public boolean matches(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain) {
      try (MatchingEventImpl event = newMatchingEvent(typeDescription)) {
        boolean matched =
            matcher.matches(
                typeDescription, classLoader, module, classBeingRedefined, protectionDomain);
        event.matched = matched;
        return matched;
      }
    }

    private MatchingEventImpl newMatchingEvent(TypeDescription target) {
      MatchingEventImpl event = new MatchingEventImpl();
      if (event.isEnabled()) {
        event.instrumentation = instrumenterClass;
        event.matcher = matcher.toString();
        event.target = target.toString();

        event.begin();
      }
      return event;
    }
  }

  @Override
  public <T> ElementMatcher<T> matcherWithEvents(
      ElementMatcher<T> matcher, String instrumenterClass) {
    return new MatcherWrapper<T>(matcher, instrumenterClass);
  }

  @Override
  public AgentBuilder.RawMatcher rawMatcherWithEvents(
      AgentBuilder.RawMatcher matcher, String instrumenterClass) {
    return new RawMatcherWrapper(matcher, instrumenterClass);
  }
}
