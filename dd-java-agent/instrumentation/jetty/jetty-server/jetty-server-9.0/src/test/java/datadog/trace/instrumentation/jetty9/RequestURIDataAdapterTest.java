package datadog.trace.instrumentation.jetty9;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.jetty.server.Request;
import org.junit.jupiter.api.Test;

class RequestURIDataAdapterTest {

  @Test
  void readsTheQuery() {
    Request request = request("/orders");
    when(request.getQueryString()).thenReturn("id=1");

    RequestURIDataAdapter adapter = new RequestURIDataAdapter(request);

    assertEquals("/orders", adapter.rawPath());
    assertEquals("id=1", adapter.rawQuery());
  }

  /** Jetty 9 can throw reading the query of a malformed request URI; the rest is still read. */
  @Test
  void treatsAnUnreadableQueryAsAbsent() {
    Request request = request("/orders");
    when(request.getQueryString()).thenThrow(new StringIndexOutOfBoundsException(-1));

    RequestURIDataAdapter adapter = new RequestURIDataAdapter(request);

    assertNull(adapter.rawQuery());
    assertNull(adapter.query());
    assertEquals("/orders", adapter.rawPath());
    assertEquals("http", adapter.scheme());
    assertEquals("example.com", adapter.host());
    assertEquals(8080, adapter.port());
  }

  private static Request request(String path) {
    Request request = mock(Request.class);
    when(request.getScheme()).thenReturn("http");
    when(request.getServerName()).thenReturn("example.com");
    when(request.getServerPort()).thenReturn(8080);
    when(request.getRequestURI()).thenReturn(path);
    return request;
  }
}
