package datadog.http.client;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_10;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.URI;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

abstract class HttpProvidersTest {

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
    HttpUrl url = HttpProviders.httpUrlParse("http://localhost");
    assertType(url);
  }

  @Test
  void testHttpUrlFromUri() {
    HttpUrl url = HttpProviders.httpUrlFrom(URI.create("http://localhost"));
    assertType(url);
  }

  @Test
  void testRequestBodyOfString() {
    HttpRequestBody body = HttpProviders.requestBodyOfString("content");
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
        HttpProviders.requestBodyGzip(HttpProviders.requestBodyOfString("content"));
    assertType(body);
  }

  @Test
  void testRequestBodyMultipart() {
    HttpRequestBody.MultipartBuilder builder = HttpProviders.requestBodyMultipart();
    assertType(builder);
  }

  private void assertType(Object builder) {
    assertNotNull(builder);
    assertTrue(builder.getClass().getName().startsWith(getImplementationPackage()));
  }

  @EnabledForJreRange(max = JAVA_10)
  static class OkHttpProvidersForkedTest extends HttpProvidersTest {
    @Override
    String getImplementationPackage() {
      return "datadog.http.client.okhttp";
    }
  }

  @EnabledForJreRange(min = JAVA_11)
  static class JdkHttpProvidersForkedTest extends HttpProvidersTest {
    @Override
    String getImplementationPackage() {
      return "datadog.http.client.jdk";
    }
  }

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
  }
}
