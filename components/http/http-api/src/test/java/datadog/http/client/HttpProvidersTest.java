package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

abstract class HttpProvidersTest {

  abstract String getImplementationPackage();

  @Test
  void testNewClientBuilder() {
    HttpRequest.Builder builder = HttpProviders.newRequestBuilder();
    assertNotNull(builder);
    assertTrue(builder.getClass().getName().startsWith(getImplementationPackage()));
  }

  @EnabledForJreRange(max = JRE.JAVA_10)
  static class OkHttpProvidersForkedTest extends HttpProvidersTest {
    @Override
    String getImplementationPackage() {
      return  "datadog.http.client.okhttp";
    }
  }

  @EnabledForJreRange(min = JRE.JAVA_11)
  static class JdkHttpProvidersForkedTest extends HttpProvidersTest {
    @Override
    String getImplementationPackage() {
      return "datadog.http.client.jdk";
    }
  }

  @EnabledForJreRange(min = JRE.JAVA_11)
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
