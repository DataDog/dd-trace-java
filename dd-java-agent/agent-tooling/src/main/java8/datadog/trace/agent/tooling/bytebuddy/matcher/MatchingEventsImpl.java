package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.MatchingEvent;
import datadog.trace.agent.tooling.bytebuddy.matcher.jfr.MatchingEvents;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.matcher.ElementMatcher;

/** Instantiated by reflection when current JDK supports JFR */
public class MatchingEventsImpl extends MatchingEvents {

  public static MatchingEvents INSTANCE = new MatchingEventsImpl();

  @Category({"Datadog", "Tracer"})
  @Name("datadog.trace.agent.Matching")
  @Label("Matching")
  static class MatchingEventImpl extends Event implements MatchingEvent {
    @Label("Matcher")
    String matcher;

    @Label("Params")
    String params;

    @Label("Target")
    String target;

    @Label("Matched")
    boolean matched;

    @Override
    public void setMatched(boolean matched) {
      this.matched = matched;
    }

    @Override
    public void close() {
      if (shouldCommit()) {
        commit();
      }
    }
  }

  @Override
  public MatchingEvent namedMatchingEvent(String name, int mode, Object data) {
    MatchingEventImpl result = new MatchingEventImpl();
    if (result.isEnabled()) {
      result.matcher = namedMatchingMode(mode);
      result.params = data.toString();

      result.begin();
      return result;
    }
    return MatchingEvent.NOOP;
  }

  private String namedMatchingMode(int mode) {
    switch (mode) {
      case NameMatchers.NAMED:
        return "exact-match";
      case NameMatchers.NAME_STARTS_WITH:
        return "starts-with";
      case NameMatchers.NAME_ENDS_WITH:
        return "ends-with";
      case NameMatchers.NOT_EXCLUDED_BY_NAME:
        return "not-excluded-by-name";
      case NameMatchers.NAMED_ONE_OF:
        return "one-of";
      case NameMatchers.NAMED_NONE_OF:
        return "none-of";
      default:
        return "";
    }
  }

  @Override
  public MatchingEvent hasInterfaceMatchingEvent(
      TypeDefinition typeDefinition, ElementMatcher<?> matcher) {
    MatchingEventImpl result = new MatchingEventImpl();
    if (result.isEnabled()) {
      result.matcher = "has-interface";
      result.params = matcher.toString();
      result.target = typeDefinition.getTypeName();

      result.begin();
      return result;
    }
    return MatchingEvent.NOOP;
  }

  @Override
  public MatchingEvent hasSuperTypeMatchingEvent(
      TypeDefinition typeDefinition, ElementMatcher<?> matcher) {
    MatchingEventImpl result = new MatchingEventImpl();
    if (result.isEnabled()) {
      result.matcher = "has-super-type";
      result.params = matcher.toString();
      result.target = typeDefinition.getTypeName();

      result.begin();
      return result;
    }
    return MatchingEvent.NOOP;
  }

  public MatchingEvent hasSuperMethodMatchingEvent(
      MethodDescription methodDescription, ElementMatcher<?> matcher) {
    MatchingEventImpl result = new MatchingEventImpl();
    if (result.isEnabled()) {
      result.matcher = "has-super-method";
      result.params = matcher.toString();
      result.target = methodDescription.toString();

      result.begin();
      return result;
    }
    return MatchingEvent.NOOP;
  }
}
