package datadog.trace.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueryParametersTest {

  /** A mutable value, turned into a string when bound. */
  static final class CountingValue {
    int conversions;

    @Override
    public String toString() {
      conversions++;
      return "value";
    }
  }

  @Test
  void valuesBeyondThePositionLimitAreNotConverted() {
    QueryParameters queryParameters = new QueryParameters();
    CountingValue kept = new CountingValue();
    CountingValue dropped = new CountingValue();

    queryParameters.bind(64, "setObject", kept);
    queryParameters.bind(65, "setObject", dropped);

    assertEquals(1, kept.conversions);
    assertEquals(0, dropped.conversions);
  }

  @Test
  void namesThatDifferByCaseOrPrefixMayBeTheSameParameter() {
    assertTrue(QueryParameters.mayBeSameParameter("ORDER_ID", "order_id"));
    assertTrue(QueryParameters.mayBeSameParameter("@order_id", "ORDER_ID"));
    assertTrue(QueryParameters.mayBeSameParameter(":order_id", "order_id"));
    assertFalse(QueryParameters.mayBeSameParameter("order_id", "order_ids"));
    assertFalse(QueryParameters.mayBeSameParameter("order_id", "customer_id"));
  }

  @Test
  void valuesBeyondTheNameLimitAreNotConverted() {
    QueryParameters queryParameters = new QueryParameters();
    for (int i = 0; i < 64; i++) {
      queryParameters.bind("name" + i, "setString", "value");
    }
    CountingValue dropped = new CountingValue();

    queryParameters.bind("name64", "setObject", dropped);

    assertEquals(0, dropped.conversions);
  }
}
