package datadog.http.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_10;
import static org.junit.jupiter.api.condition.JRE.JAVA_11;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

abstract class HttpProvidersTest {
  abstract String getImplementationPackage();

  @Test
  void testProviderLookup() {
    // Check runtime type
    HttpProvider httpProvider = HttpProviders.get();
    assertType(httpProvider);
    // Downgrade to compatibility mode
    HttpProviders.forceCompatClient();
    httpProvider = HttpProviders.get();
    assertCompatType(httpProvider);
    // Ensure downgrade is idempotent
    HttpProviders.forceCompatClient();
    httpProvider = HttpProviders.get();
    assertCompatType(httpProvider);
  }

  private void assertType(Object type) {
    assertNotNull(type);
    assertTrue(type.getClass().getName().startsWith(getImplementationPackage()));
  }

  private void assertCompatType(Object type) {
    assertNotNull(type);
    assertTrue(type.getClass().getName().startsWith("datadog.http.client.okhttp"));
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
  }
}
