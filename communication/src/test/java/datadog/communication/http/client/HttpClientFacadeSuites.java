package datadog.communication.http.client;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

public final class HttpClientFacadeSuites {
  private HttpClientFacadeSuites() {}

  @Suite
  @SuiteDisplayName("HTTP Facade Suite [netty]")
  @SelectClasses(HttpClientFacadeContractTest.class)
  @ConfigurationParameter(key = HttpClientFacadeContractTest.CLIENT_IMPL_PARAMETER, value = "netty")
  public static class NettyHttpClientTest {}

  @Suite
  @SuiteDisplayName("HTTP Facade Suite [apache-async-http-client5]")
  @SelectClasses(HttpClientFacadeContractTest.class)
  @ConfigurationParameter(
      key = HttpClientFacadeContractTest.CLIENT_IMPL_PARAMETER,
      value = "apache-async-http-client5")
  public static class ApacheAsyncHttpClientTest {}
}
