package datadog.trace.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
