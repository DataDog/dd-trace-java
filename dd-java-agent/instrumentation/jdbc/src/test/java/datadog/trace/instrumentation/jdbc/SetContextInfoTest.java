package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionContext;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SetContextInfoTest extends AbstractInstrumentationTest {

  @Test
  void skipsContextInfoWhenTracingIsMuted() throws Exception {
    List<String> calls = new ArrayList<>();
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  calls.add(method.getName());
                  throw new UnsupportedOperationException(method.getName());
                });
    JDBCDecorator decorator = new JDBCDecorator();

    // as when a pool validates a connection: spans started here have no sampling decision
    try (ContextScope scope = activateSpan(blackholeSpan())) {
      decorator.setContextInfo(connection, new JDBCConnectionContext(DBInfo.DEFAULT));
    }

    assertEquals(new ArrayList<String>(), calls, "no round trip without a trace to correlate");
    assertFalse(loggedInjectionError(decorator), "an injection error was logged");
  }

  /** The once-only warning flag: a spurious warning would hide later real injection failures. */
  private static boolean loggedInjectionError(JDBCDecorator decorator) throws Exception {
    Field field = JDBCDecorator.class.getDeclaredField("loggedInjectionError");
    field.setAccessible(true);
    return field.getBoolean(decorator);
  }
}
