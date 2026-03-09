package datadog.communication.http.client;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

import static datadog.communication.http.client.HttpClientContractTest.AHC;
import static datadog.communication.http.client.HttpClientContractTest.CLIENT_IMPL_PARAMETER;
import static datadog.communication.http.client.HttpClientContractTest.JETTY;
import static datadog.communication.http.client.HttpClientContractTest.NETTY;

public final class HttpClientFacadeSuites {
  private HttpClientFacadeSuites() {}

  @Suite
  @SuiteDisplayName("HTTP Facade Suite [netty]")
  @SelectClasses(HttpClientContractTest.class)
  @ConfigurationParameter(key = CLIENT_IMPL_PARAMETER, value = NETTY)
  public static class NettyHttpClientTest {}

  @Suite
  @SuiteDisplayName("HTTP Facade Suite [apache-async-http-client5]")
  @SelectClasses(HttpClientContractTest.class)
  @ConfigurationParameter(key = CLIENT_IMPL_PARAMETER, value = AHC)
  public static class ApacheAsyncHttpClientTest {}

  @Suite
  @SuiteDisplayName("HTTP Facade Suite [jetty-http-client]")
  @SelectClasses(HttpClientContractTest.class)
  @ConfigurationParameter(key = CLIENT_IMPL_PARAMETER, value = JETTY)
  public static class JettyHttpClientTest {}
}
