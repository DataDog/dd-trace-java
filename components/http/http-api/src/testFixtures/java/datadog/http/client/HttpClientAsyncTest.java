package datadog.http.client;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;

@ExtendWith(MockServerExtension.class)
public class HttpClientAsyncTest {
  private static final int TIMEOUT_SECONDS = 5;
  private ClientAndServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeEach
  void setUp(ClientAndServer server) {
    this.server = server;
    this.client = HttpClient.newBuilder().build();
    this.baseUrl = "http://localhost:" + server.getPort();
  }

  @AfterEach
  void tearDown() {
    this.server.reset();
  }

  @Test
  void testExecuteAsyncSuccess() throws Exception {
    org.mockserver.model.HttpRequest expectedRequest =
        request().withMethod("GET").withPath("/test");
    this.server.when(expectedRequest).respond(response().withStatusCode(200).withBody("success"));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();

    CompletableFuture<HttpResponse> future = this.client.executeAsync(request);

    HttpResponse response = future.get(TIMEOUT_SECONDS, SECONDS);
    assertNotNull(response);
    assertEquals(200, response.code());
    assertTrue(response.isSuccessful());
    assertEquals("success", response.bodyAsString());

    this.server.verify(expectedRequest);
    response.close();
  }

  @Test
  void testExecuteAsyncHttpError() throws Exception {
    org.mockserver.model.HttpRequest expectedRequest =
        request().withMethod("GET").withPath("/notfound");
    this.server.when(expectedRequest).respond(response().withStatusCode(404));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/notfound");
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();

    CompletableFuture<HttpResponse> future = this.client.executeAsync(request);

    // HTTP errors (4xx, 5xx) should complete normally, not exceptionally
    HttpResponse response = future.get(TIMEOUT_SECONDS, SECONDS);
    assertNotNull(response);
    assertEquals(404, response.code());

    this.server.verify(expectedRequest);
    response.close();
  }

  @Test
  void testExecuteAsyncWithListener() throws Exception {
    org.mockserver.model.HttpRequest expectedRequest =
        request().withMethod("GET").withPath("/test");
    this.server.when(expectedRequest).respond(response().withStatusCode(200));

    AtomicBoolean startCalled = new AtomicBoolean(false);
    AtomicBoolean endCalled = new AtomicBoolean(false);
    AtomicReference<HttpResponse> capturedResponse = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request =
        HttpRequest.newBuilder()
            .url(url)
            .get()
            .listener(
                new HttpRequestListener() {
                  @Override
                  public void onRequestStart(HttpRequest request) {
                    startCalled.set(true);
                  }

                  @Override
                  public void onRequestEnd(HttpRequest request, HttpResponse response) {
                    endCalled.set(true);
                    capturedResponse.set(response);
                    latch.countDown();
                  }

                  @Override
                  public void onRequestFailure(HttpRequest request, IOException exception) {
                    fail("Should not fail");
                  }
                })
            .build();

    this.client.executeAsync(request);

    assertTrue(latch.await(TIMEOUT_SECONDS, SECONDS), "Listener should be called");
    assertTrue(startCalled.get(), "onRequestStart should be called");
    assertTrue(endCalled.get(), "onRequestEnd should be called");
    assertNotNull(capturedResponse.get());
    assertEquals(200, capturedResponse.get().code());

    this.server.verify(expectedRequest);
  }

  @Test
  void testExecuteAsyncWithListenerOnFailure() throws Exception {
    // Use an invalid port to cause connection failure
    HttpUrl url = HttpUrl.parse("http://localhost:1/test");

    AtomicBoolean startCalled = new AtomicBoolean(false);
    AtomicBoolean failureCalled = new AtomicBoolean(false);
    AtomicReference<IOException> capturedException = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    HttpRequest request =
        HttpRequest.newBuilder()
            .url(url)
            .get()
            .listener(
                new HttpRequestListener() {
                  @Override
                  public void onRequestStart(HttpRequest request) {
                    startCalled.set(true);
                  }

                  @Override
                  public void onRequestEnd(HttpRequest request, HttpResponse response) {
                    fail("Should not succeed");
                  }

                  @Override
                  public void onRequestFailure(HttpRequest request, IOException exception) {
                    failureCalled.set(true);
                    capturedException.set(exception);
                    latch.countDown();
                  }
                })
            .build();

    CompletableFuture<HttpResponse> future = this.client.executeAsync(request);

    assertTrue(latch.await(TIMEOUT_SECONDS, SECONDS), "Listener should be called");
    assertTrue(startCalled.get(), "onRequestStart should be called");
    assertTrue(failureCalled.get(), "onRequestFailure should be called");
    assertNotNull(capturedException.get());

    // The future should also complete exceptionally
    try {
      future.get(TIMEOUT_SECONDS, SECONDS);
      fail("Future should complete exceptionally");
    } catch (ExecutionException e) {
      assertInstanceOf(IOException.class, e.getCause());
    }
  }

  @Test
  void testExecuteAsyncComposition() throws Exception {
    org.mockserver.model.HttpRequest expectedRequest =
        request().withMethod("GET").withPath("/test");
    this.server.when(expectedRequest).respond(response().withStatusCode(200).withBody("42"));

    HttpUrl url = HttpUrl.parse(this.baseUrl + "/test");
    HttpRequest request = HttpRequest.newBuilder().url(url).get().build();

    // Test thenApply composition
    CompletableFuture<Integer> future =
        this.client
            .executeAsync(request)
            .thenApply(
                response -> {
                  try {
                    return Integer.parseInt(response.bodyAsString().trim());
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  } finally {
                    response.close();
                  }
                });

    Integer result = future.get(TIMEOUT_SECONDS, SECONDS);
    assertEquals(42, result);

    this.server.verify(expectedRequest);
  }

  @Test
  void testExecuteAsyncMultipleRequests() throws Exception {
    org.mockserver.model.HttpRequest expectedRequest1 =
        request().withMethod("GET").withPath("/test1");
    org.mockserver.model.HttpRequest expectedRequest2 =
        request().withMethod("GET").withPath("/test2");
    this.server
        .when(expectedRequest1)
        .respond(response().withStatusCode(200).withBody("response1"));
    this.server
        .when(expectedRequest2)
        .respond(response().withStatusCode(200).withBody("response2"));

    HttpRequest request1 =
        HttpRequest.newBuilder().url(HttpUrl.parse(this.baseUrl + "/test1")).get().build();
    HttpRequest request2 =
        HttpRequest.newBuilder().url(HttpUrl.parse(this.baseUrl + "/test2")).get().build();

    // Execute both requests concurrently
    CompletableFuture<HttpResponse> future1 = this.client.executeAsync(request1);
    CompletableFuture<HttpResponse> future2 = this.client.executeAsync(request2);

    // Wait for both
    CompletableFuture.allOf(future1, future2).get(TIMEOUT_SECONDS, SECONDS);

    HttpResponse response1 = future1.get();
    HttpResponse response2 = future2.get();

    assertEquals("response1", response1.bodyAsString());
    assertEquals("response2", response2.bodyAsString());

    response1.close();
    response2.close();
  }
}
