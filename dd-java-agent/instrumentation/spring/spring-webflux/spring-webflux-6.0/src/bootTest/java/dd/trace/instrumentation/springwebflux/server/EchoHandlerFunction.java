package dd.trace.instrumentation.springwebflux.server;

import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public class EchoHandlerFunction implements HandlerFunction<ServerResponse> {
  private final EchoHandler echoHandler;

  public EchoHandlerFunction(EchoHandler echoHandler) {
    this.echoHandler = echoHandler;
  }

  @Override
  public Mono<ServerResponse> handle(ServerRequest request) {
    return echoHandler.echo(request);
  }
}
