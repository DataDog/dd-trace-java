package dd.trace.instrumentation.springwebflux.server;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
public class WsHandler implements WebSocketHandler {

  volatile WebSocketSession activeSession;

  @Override
  public Mono<Void> handle(WebSocketSession webSocketSession) {
    // An echo server the closes after the first echoed message
    activeSession = webSocketSession;
    synchronized (this) {
      notifyAll();
    }
    return webSocketSession
        .receive()
        .map(
            msg -> {
              if (msg.getType() == WebSocketMessage.Type.TEXT) {
                return webSocketSession.textMessage(msg.getPayloadAsText());
              }
              byte[] bytes = new byte[msg.getPayload().readableByteCount()];
              msg.getPayload().read(bytes);
              return webSocketSession.binaryMessage(
                  dataBufferFactory -> dataBufferFactory.wrap(bytes));
            })
        .flatMap(
            msg ->
                webSocketSession
                    .send(Mono.just(msg))
                    .doFinally(
                        ignored -> {
                          synchronized (this) {
                            activeSession = null;
                            notifyAll();
                          }
                        }))
        .then();
  }

  public void awaitExchangeComplete() {
    synchronized (this) {
      if (activeSession == null) {
        return;
      }
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void awaitConnected() {
    synchronized (this) {
      if (activeSession != null) {
        return;
      }
      try {
        wait();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assert activeSession != null;
  }
}
