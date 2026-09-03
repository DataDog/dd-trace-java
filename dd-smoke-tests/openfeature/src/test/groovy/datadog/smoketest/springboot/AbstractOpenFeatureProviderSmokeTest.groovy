package datadog.smoketest.springboot

import datadog.remoteconfig.Product
import datadog.smoketest.AbstractServerSmokeTest
import datadog.trace.agent.test.server.http.TestHttpServer.HandlerApi.RequestApi
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Paths
import spock.lang.Shared

abstract class AbstractOpenFeatureProviderSmokeTest extends AbstractServerSmokeTest {

  @Shared
  protected final rcConfig = new JsonSlurper().parse(fetchResource('ffe-system-test-data/ufc-config.json')) as Map<String, Object>

  @Shared
  protected final rcPayload = JsonOutput.toJson(rcConfig)

  @Override
  ProcessBuilder createProcessBuilder() {
    setRemoteConfig('datadog/2/FFE_FLAGS/1/config', rcPayload)

    final springBootShadowJar = System.getProperty(
      'datadog.smoketest.openfeature.application.path',
      System.getProperty('datadog.smoketest.springboot.shadowJar.path')
      )
    final agentJar = System.getProperty('datadog.smoketest.openfeature.agent.path', shadowJarPath)
    assert Files.isRegularFile(Paths.get(springBootShadowJar))
    assert Files.isRegularFile(Paths.get(agentJar))
    final command = [javaPath()]
    command.addAll(defaultJavaProperties.collect {
      it.startsWith('-javaagent:') ? "-javaagent:${agentJar}".toString() : it
    })
    command.add('-Ddd.trace.debug=true')
    command.add('-Ddd.remote_config.enabled=true')
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.addAll(['-jar', springBootShadowJar, "--server.port=${httpPort}".toString()])
    final builder = new ProcessBuilder(command).directory(new File(buildDirectory))
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED', 'true')
    builder.environment().put('DD_EXPERIMENTAL_FLAGGING_PROVIDER_SPAN_ENRICHMENT_ENABLED', 'true')
    builder.environment().put('DD_FEATURE_FLAGS_CONFIGURATION_SOURCE', 'remote_config')
    return builder
  }

  @Override
  Closure decodedTracesCallback() {
    return {}
  }

  @Override
  Closure decodedEvpProxyMessageCallback() {
    return { String path, RequestApi request ->
      if (!path.contains('api/v2/exposures')) {
        return null
      }
      return new JsonSlurper().parse(request.body)
    }
  }

  protected static URL fetchResource(final String name) {
    return Thread.currentThread().getContextClassLoader().getResource(name)
  }

  protected static Set<Product> decodeProducts(final Map<String, Object> request) {
    return request.client.products.collect { Product.valueOf(it) }
  }

  protected static long decodeCapabilities(final Map<String, Object> request) {
    final clientCapabilities = request.client.capabilities as byte[]
    long capabilities = 0l
    for (int i = 0; i < clientCapabilities.length; i++) {
      capabilities |= (clientCapabilities[i] & 0xFFL) << ((clientCapabilities.length - i - 1) * 8)
    }
    return capabilities
  }

  protected static boolean hasCapability(final long capabilities, final long test) {
    return (capabilities & test) > 0
  }
}
