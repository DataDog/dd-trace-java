package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UnparseableURIAdapterTest {

  @Test
  void shouldReturnRawUriOnly() {
    String uriStr = "http://myurl/path?query=value#fragment";
    UnparseableURIDataAdapter uriAdapter = new UnparseableURIDataAdapter(uriStr);

    assertFalse(uriAdapter.isValid());
    assertNull(uriAdapter.host());
    assertEquals(0, uriAdapter.port());
    assertNull(uriAdapter.fragment());
    assertNull(uriAdapter.path());
    assertNull(uriAdapter.query());
    assertNull(uriAdapter.rawQuery());
    assertNull(uriAdapter.scheme());
    assertNull(uriAdapter.rawPath());
    assertEquals(uriStr, uriAdapter.raw());
  }
}
