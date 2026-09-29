package datadog.trace.instrumentation.servlet3;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.ApplicationModule;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetHttpSessionAdviceTest {

  private final List<String> moduleCalls = new ArrayList<>();
  private ApplicationModule previousModule;

  @BeforeEach
  void registerModule() {
    previousModule = InstrumentationBridge.APPLICATION;
    InstrumentationBridge.APPLICATION =
        proxy(
            ApplicationModule.class,
            (proxy, method, args) -> {
              moduleCalls.add(method.getName());
              return null;
            });
  }

  @AfterEach
  void restoreModule() {
    InstrumentationBridge.APPLICATION = previousModule;
  }

  /**
   * Request copies such as Wicket's {@code ServletRequestCopy} or Atmosphere's {@code NoOpsRequest}
   * return a session but have no servlet context. The advice must not use that null context as a
   * context store key (the weak map behind it throws on null keys).
   */
  @Test
  void ignoresRequestWithoutServletContext() {
    HttpServletRequest request = proxy(HttpServletRequest.class, (proxy, method, args) -> null);
    HttpSession session = proxy(HttpSession.class, (proxy, method, args) -> null);

    assertDoesNotThrow(
        () ->
            IastOptOutHttpServletRequest3Instrumentation.GetHttpSessionAdvice.onExit(
                request, session));

    assertEquals(emptyList(), moduleCalls);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }
}
