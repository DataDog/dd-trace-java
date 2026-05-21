package com.datadog.appsec.sca;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScaReachabilitySystem#findCallsite(String)}.
 *
 * <p>Design notes on testability:
 *
 * <ul>
 *   <li>This test class is in {@code com.datadog.appsec.sca.*}, which {@link
 *       datadog.trace.util.stacktrace.AbstractStackWalker#isNotDatadogTraceStackElement} treats as
 *       agent code and skips. That means test frames are filtered before reaching the {@code
 *       pastVulnerableClass} state machine.
 *   <li>To exercise the "skip the vulnerable class and return the caller" path, the vulnerable
 *       class must be a non-agent class that IS on the stack. {@code java.lang.Thread} is always
 *       Frame 0 of {@code getStackTrace()} and is not filtered.
 *   <li>The end-to-end callsite assertion (callsite != vulnerable class) is validated in {@link
 *       ScaReachabilityMethodLevelTest#injectMethodCallbacks_callbackFiredOnMethodCall}.
 * </ul>
 */
class ScaReachabilitySystemCallsiteTest {

  @Test
  void findCallsite_returnsNullWhenVulnerableClassIsNotInStack() {
    // If the vulnerable class is never on the call stack, pastVulnerableClass never becomes true,
    // and findCallsite returns null. This triggers the fallback path in the handler
    // (reports the vulnerable symbol itself instead of a callsite).
    StackTraceElement result = ScaReachabilitySystem.findCallsite("com.example.ClassNotOnStack");

    // pastVulnerableClass never fires → no frame is ever returned
    assertNull(result, "Should return null when vulnerable class is not on the stack");
  }

  @Test
  void findCallsite_skipsVulnerableClassAndReturnsFrameAboveIt() {
    // java.lang.Thread is always Frame 0 of getStackTrace(), and it is NOT filtered
    // by isNotDatadogTraceStackElement (not a datadog.* class). Using it as the
    // "vulnerable class" exercises the full "find frame above vulnerable class" path:
    //
    //   Frame 0: java.lang.Thread  ← cls == vulnerableClass → pastVulnerableClass = true, skip
    //   Frame 1: ScaReachabilitySystem.findCallsite   ← agent frame, skip
    //   Frame 2: this test class                      ← agent frame (com.datadog.appsec.*), skip
    //   Frame 3+: JUnit runner (org.junit.*)          ← first non-agent frame → RETURNED
    StackTraceElement result = ScaReachabilitySystem.findCallsite("java.lang.Thread");

    assertNotNull(
        result,
        "Should find a frame above java.lang.Thread — a JUnit runner frame should be present");
    assertNotEquals(
        "java.lang.Thread",
        result.getClassName(),
        "Callsite must not be the vulnerable class (java.lang.Thread) itself");
    assertNotEquals(
        true,
        result.getClassName().startsWith("datadog."),
        "Callsite must not be an agent (datadog.*) frame");
  }
}
