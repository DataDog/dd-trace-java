package dd.trace.instrumentation.springwebflux.server

import datadog.trace.api.Trace
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/**
 * Note: this class has to stay outside of 'datadog.*' package because we need
 * it transformed by {@code @Trace} annotation.
 */
@Component
class EchoHandler {
  Mono<ServerResponse> echo(ServerRequest request) {
    doSomething()
    return ServerResponse.accepted().contentType(MediaType.TEXT_PLAIN)
      .body(request.bodyToMono(String), String)
  }

  @Trace(operationName = "echo", resourceName = "echo")
  private void doSomething() {
    // we trace here otherwise the span created will stay alive until the mono returned by the handler
    // is terminated
  }
}
