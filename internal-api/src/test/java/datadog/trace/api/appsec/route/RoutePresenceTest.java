package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class RoutePresenceTest {

  @TableTest({
    "Scenario      | Value | Expected             ",
    "not bound     |       | ABSENT               ",
    "empty         | ''    | ABSENT               ",
    "only slash    | '/'   | ABSENT_TRAILING_SLASH",
    "only slashes  | '//'  | ABSENT_TRAILING_SLASH",
    "segment       | 'a'   | PRESENT              ",
    "leading slash | '/a'  | PRESENT              "
  })
  void catchAllPresenceFromValue(String value, RoutePresence expected) {
    assertEquals(expected, RoutePresence.ofCatchAllValue(value));
  }

  @TableTest({
    "Scenario   | Value | Expected",
    "not bound  |       | ABSENT  ",
    "empty      | ''    | ABSENT  ",
    "only slash | '/'   | PRESENT ",
    "value      | 'a'   | PRESENT "
  })
  void optionalParamPresenceFromValue(String value, RoutePresence expected) {
    assertEquals(expected, RoutePresence.ofBoundValue(value));
  }
}
