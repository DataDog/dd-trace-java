package datadog.trace.instrumentation.jdbc;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The capture is off by default. */
class QueryParametersCaptureDisabledTest extends QueryParametersTestSupport {

  @Test
  void parametersAreNotCapturedByDefault() throws SQLException {
    try (PreparedStatement statement =
        h2.prepareStatement("SELECT * FROM orders WHERE id = ? AND status = ?")) {
      statement.setLong(1, 48213L);
      statement.setString(2, "SHIPPED");
      statement.executeQuery().close();
    }

    assertEquals(new TreeMap<>(), parameterTags(preparedStatementSpans(1).get(0)));
  }

  @Test
  void statementsGetNoCaptureState() throws SQLException {
    assertFalse(
        new PreparedStatementInstrumentation()
            .contextStore()
            .containsKey("java.sql.PreparedStatement"));
    try (PreparedStatement statement = h2.prepareStatement("SELECT 1")) {
      // the DBQueryInfo field is on the parent class, which implements Statement
      assertEquals(emptyList(), injectedContextFields(statement.getClass()));
    }
  }
}
