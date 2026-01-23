package datadog.communication.http.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpListenerTest {

  @Test
  void testListenerCallbacks() {
    TestListener listener = new TestListener();
    HttpUrl url = HttpUrl.parse("http://localhost:8080/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    // Simulate request lifecycle
    listener.onRequestStart(request);
    assertEquals(1, listener.events.size());
    assertEquals("start", listener.events.get(0));

    // Successful response
    listener.onRequestEnd(request, null);
    assertEquals(2, listener.events.size());
    assertEquals("end", listener.events.get(1));
  }

  @Test
  void testListenerOnFailure() {
    TestListener listener = new TestListener();
    HttpUrl url = HttpUrl.parse("http://localhost:8080/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    listener.onRequestStart(request);

    IOException error = new IOException("Connection timeout");
    listener.onRequestFailure(request, error);

    assertEquals(2, listener.events.size());
    assertEquals("start", listener.events.get(0));
    assertEquals("failure", listener.events.get(1));
    assertEquals(error, listener.lastError);
  }

  @Test
  void testNoOpListener() {
    // Verify NONE listener doesn't throw exceptions
    HttpUrl url = HttpUrl.parse("http://localhost:8080/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    HttpListener.NONE.onRequestStart(request);
    HttpListener.NONE.onRequestEnd(request, null);
    HttpListener.NONE.onRequestFailure(request, new IOException("test"));

    // If we get here without exceptions, the no-op listener works
    assertTrue(true);
  }

  @Test
  void testListenerWithResponse() {
    TestListener listener = new TestListener();
    HttpUrl url = HttpUrl.parse("http://localhost:8080/test");
    HttpRequest request = HttpRequest.newBuilder()
        .url(url)
        .get()
        .build();

    listener.onRequestStart(request);

    // In real usage, response would be an actual HttpResponse instance
    // For this test, we just verify the callback is invoked
    listener.onRequestEnd(request, null);

    assertNotNull(listener.lastRequest);
    assertEquals(url.url(), listener.lastRequest.url().url());
  }

  private static class TestListener implements HttpListener {
    final List<String> events = new ArrayList<>();
    HttpRequest lastRequest;
    IOException lastError;

    @Override
    public void onRequestStart(HttpRequest request) {
      events.add("start");
      lastRequest = request;
    }

    @Override
    public void onRequestEnd(HttpRequest request, HttpResponse response) {
      events.add("end");
      lastRequest = request;
    }

    @Override
    public void onRequestFailure(HttpRequest request, IOException exception) {
      events.add("failure");
      lastRequest = request;
      lastError = exception;
    }
  }
}
