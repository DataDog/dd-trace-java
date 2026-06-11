package datadog.trace.agent.test.scopediag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StackFilterTest {

  private static StackTraceElement frame(String cls, String method) {
    return new StackTraceElement(cls, method, "Src.java", 1);
  }

  @Test
  void dropsPlumbingAndKeepsAppFrames() {
    StackTraceElement[] raw = {
      frame("java.lang.Thread", "getStackTrace"),
      frame("datadog.trace.agent.test.scopediag.ScopeDiagnostics", "event"),
      frame("datadog.trace.core.scopemanager.ScopeContinuation", "register"),
      frame("java.util.concurrent.ThreadPoolExecutor", "execute"),
      frame("com.app.Service", "doWork"),
      frame("com.app.Main", "main"),
    };

    StackTraceElement[] filtered = new StackFilter(6).filter(raw);

    assertEquals(2, filtered.length);
    assertEquals("com.app.Service", filtered[0].getClassName());
    assertEquals("com.app.Main", filtered[1].getClassName());
  }

  @Test
  void respectsMaxFrames() {
    StackTraceElement[] raw = {
      frame("com.app.A", "a"), frame("com.app.B", "b"), frame("com.app.C", "c"),
    };

    assertEquals(2, new StackFilter(2).filter(raw).length);
  }

  @Test
  void handlesNullStack() {
    assertEquals(0, new StackFilter(6).filter(null).length);
  }

  @Test
  void keepsScopeManagerFreeStacks() {
    StackTraceElement[] raw = {frame("com.app.Only", "here")};
    StackTraceElement[] filtered = new StackFilter(6).filter(raw);
    assertEquals(1, filtered.length);
    assertTrue(filtered[0].getClassName().startsWith("com.app"));
  }
}
