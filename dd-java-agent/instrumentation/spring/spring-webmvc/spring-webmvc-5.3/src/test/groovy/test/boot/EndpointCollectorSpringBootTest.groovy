package test.boot

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.telemetry.Endpoint
import datadog.trace.api.telemetry.EndpointCollector
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.config.annotation.EnableWebMvc

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ENDPOINT_DISCOVERY
import static datadog.trace.api.config.AppSecConfig.API_SECURITY_ENDPOINT_COLLECTION_ENABLED

@SpringBootTest(classes = DiscoveryController)
@EnableWebMvc
@AutoConfigureMockMvc
class EndpointCollectorSpringBootTest extends InstrumentationSpecification {

  @Controller
  static class DiscoveryController {

    @RequestMapping(value = "/discovery",
    method = [RequestMethod.POST, RequestMethod.PATCH, RequestMethod.PUT],
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity discovery() {
      HttpServerTest.controller(ENDPOINT_DISCOVERY) {
        new ResponseEntity(ENDPOINT_DISCOVERY.body, HttpStatus.valueOf(ENDPOINT_DISCOVERY.status))
      }
    }
  }

  @Override
  protected void configurePreAgent() {
    injectSysConfig(API_SECURITY_ENDPOINT_COLLECTION_ENABLED, "true")
  }

  void 'test endpoint discovery'() {
    when:
    final endpoints = EndpointCollector.get().drain().toList().findAll { it.path == ENDPOINT_DISCOVERY.path }

    then:
    final discovery = endpoints.collectEntries { [(it.method): it] } as Map<String, Endpoint>
    discovery.keySet().containsAll([Endpoint.Method.POST, Endpoint.Method.PATCH, Endpoint.Method.PUT])
    discovery.values().each {
      assert it.path == ENDPOINT_DISCOVERY.path
      assert it.type == Endpoint.Type.REST
      assert it.operation == Endpoint.Operation.HTTP_REQUEST
      assert it.requestBodyType.containsAll([MediaType.APPLICATION_JSON_VALUE])
      assert it.responseBodyType.containsAll([MediaType.TEXT_PLAIN_VALUE])
      assert it.metadata['handler'] == 'test.boot.EndpointCollectorSpringBootTest$DiscoveryController#discovery()'
    }
  }
}
