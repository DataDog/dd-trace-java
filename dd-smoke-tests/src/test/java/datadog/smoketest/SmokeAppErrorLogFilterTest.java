package datadog.smoketest;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Docker-free unit tests for {@link AbstractSmokeApp}'s default error-log predicate. */
class SmokeAppErrorLogFilterTest {

  @Test
  void flagsErrorAndAssertionLines() {
    Predicate<String> isError = AbstractSmokeApp.defaultErrorLogFilter(emptyList());

    assertTrue(isError.test("2026-07-14 12:00:00 ERROR o.e.SomeClass - boom"), "ERROR line");
    assertTrue(isError.test("junit ASSERTION FAILED: expected X"), "assertion line");
    assertTrue(
        isError.test("Failed to handle exception in instrumentation"), "instrumentation failure");
    assertFalse(isError.test("INFO all good"), "info line is not an error");
    assertFalse(isError.test("WARN heads up"), "warn line is not an error");
  }

  @Test
  void respectsAllowlist() {
    Predicate<String> isError =
        AbstractSmokeApp.defaultErrorLogFilter(singletonList("known flaky ERROR"));

    assertFalse(isError.test("this is a known flaky ERROR we tolerate"), "allowlisted line");
    assertTrue(isError.test("a real ERROR here"), "non-allowlisted error still flagged");
  }
}
