package datadog.http.client;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_10;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.URI;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

abstract class HttpProvidersTest {
  private static final String URL_EXAMPLE = "http://localhost";
  private static final String CONTENT_EXAMPLE = "content";

  abstract String getImplementationPackage();

  @ParameterizedTest(name = "[{index}] {0} builder")
  @MethodSource("builders")
  void testNewBuilder(String type, Supplier<?> builderSupplier) {
    Object builder = builderSupplier.get();
    assertType(builder);
  }

  static Stream<Arguments> builders() {
    return Stream.of(
        arguments("client", (Supplier<?>) HttpProviders::newClientBuilder),
        arguments("request", (Supplier<?>) HttpProviders::newRequestBuilder),
        arguments("url", (Supplier<?>) HttpProviders::newUrlBuilder));
  }

  @Test
  void testHttpUrlParse() {
    HttpUrl url = HttpProviders.httpUrlParse(URL_EXAMPLE);
    assertType(url);
  }

  @Test
  void testHttpUrlParseInvalidUrl() {
    // An invalid URL causes the underlying parse() to throw IllegalArgumentException,
    // wrapped as InvocationTargetException. HttpProviders unwraps and re-throws it.
    assertThrows(
        IllegalArgumentException.class, () -> HttpProviders.httpUrlParse("not a valid url"));
  }

  @Test
  void testHttpUrlFromUri() {
    HttpUrl url = HttpProviders.httpUrlFrom(URI.create(URL_EXAMPLE));
    assertType(url);
  }

  @Test
  void testRequestBodyOfString() {
    HttpRequestBody body = HttpProviders.requestBodyOfString(CONTENT_EXAMPLE);
    assertType(body);
  }

  @Test
  void testRequestBodyOfBytes() {
    HttpRequestBody body = HttpProviders.requestBodyOfBytes(new byte[0]);
    assertType(body);
  }

  @Test
  void testRequestBodyOfByteBuffers() {
    HttpRequestBody body = HttpProviders.requestBodyOfByteBuffers(emptyList());
    assertType(body);
  }

  @Test
  void testRequestBodyGzip() {
    HttpRequestBody body =
        HttpProviders.requestBodyGzip(HttpProviders.requestBodyOfString(CONTENT_EXAMPLE));
    assertType(body);
  }

  @Test
  void testRequestBodyMultipart() {
    HttpRequestBody.MultipartBuilder builder = HttpProviders.requestBodyMultipart();
    assertType(builder);
  }

  @Test
  void testCachedProviders() {
    // First calls — populate all lazy-init caches
    HttpProviders.newClientBuilder();
    HttpProviders.newRequestBuilder();
    HttpProviders.newUrlBuilder();
    HttpProviders.httpUrlParse(URL_EXAMPLE);
    HttpProviders.httpUrlFrom(URI.create(URL_EXAMPLE));
    HttpProviders.requestBodyOfString(CONTENT_EXAMPLE);
    HttpProviders.requestBodyOfBytes(new byte[0]);
    HttpProviders.requestBodyOfByteBuffers(emptyList());
    HttpProviders.requestBodyGzip(HttpProviders.requestBodyOfString(CONTENT_EXAMPLE));
    HttpProviders.requestBodyMultipart();
    // Second calls — hit the non-null (cached) branch for every lazy field
    assertNotNull(HttpProviders.newClientBuilder());
    assertNotNull(HttpProviders.newRequestBuilder());
    assertNotNull(HttpProviders.newUrlBuilder());
    assertNotNull(HttpProviders.httpUrlParse(URL_EXAMPLE));
    assertNotNull(HttpProviders.httpUrlFrom(URI.create(URL_EXAMPLE)));
    assertNotNull(HttpProviders.requestBodyOfString(CONTENT_EXAMPLE));
    assertNotNull(HttpProviders.requestBodyOfBytes(new byte[0]));
    assertNotNull(HttpProviders.requestBodyOfByteBuffers(emptyList()));
    assertNotNull(
        HttpProviders.requestBodyGzip(HttpProviders.requestBodyOfString(CONTENT_EXAMPLE)));
    assertNotNull(HttpProviders.requestBodyMultipart());
  }

  private void assertType(Object builder) {
    assertNotNull(builder);
    assertTrue(builder.getClass().getName().startsWith(getImplementationPackage()));
  }

  @Disabled
  @EnabledForJreRange(max = JAVA_10)
  static class OkHttpProvidersForkedTest extends HttpProvidersTest {
    @Override
    String getImplementationPackage() {
      return "datadog.http.client.okhttp";
    }
  }

  @Disabled
  @EnabledForJreRange(min = JAVA_11)
  static class JdkHttpProvidersForkedTest extends HttpProvidersTest {
    @Override
    String getImplementationPackage() {
      return "datadog.http.client.jdk";
    }
  }

  @Disabled
  @EnabledForJreRange(min = JAVA_11)
  static class HttpProvidersCompatForkedTest extends HttpProvidersTest {
    @BeforeAll
    static void beforeAll() {
      HttpProviders.forceCompatClient();
    }

    @Override
    String getImplementationPackage() {
      return "datadog.http.client.okhttp";
    }

    @Test
    void testForceCompatClientIsIdempotent() {
      // compatibilityMode is already true — second call must hit early return (no NPE, no reset)
      HttpProviders.forceCompatClient();
      assertNotNull(HttpProviders.newClientBuilder());
    }
  }
}
