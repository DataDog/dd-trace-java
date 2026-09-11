package datadog.trace.bootstrap.instrumentation.decorator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.Test;

class DatabaseClientDecoratorNullDbTypeTest {

  private final AgentSpan span = mock(AgentSpan.class);
  private final DatabaseClientDecorator<Object> decorator =
      new DatabaseClientDecorator<Object>() {
        @Override
        protected String[] instrumentationNames() {
          return new String[] {"test"};
        }

        @Override
        protected CharSequence spanType() {
          return "test-type";
        }

        @Override
        protected CharSequence component() {
          return "test-component";
        }

        @Override
        protected String service() {
          return "test-service";
        }

        @Override
        protected String dbType() {
          return null;
        }

        @Override
        protected String dbUser(Object connection) {
          return null;
        }

        @Override
        protected String dbInstance(Object connection) {
          return null;
        }

        @Override
        protected CharSequence dbHostname(Object connection) {
          return null;
        }
      };

  @Test
  void processDatabaseTypeWithNullDbTypeDoesNotThrowOrTag() {
    assertDoesNotThrow(() -> decorator.processDatabaseType(span, null));

    verify(span, never()).setTag(anyString(), anyString());
  }

  @Test
  void dbServiceWithNullDbTypeReturnsNull() {
    assertNull(decorator.dbService(null, null));
  }
}
