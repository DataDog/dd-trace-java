import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan

import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.Session

class EndpointWrapper extends Endpoint {

  EndpointWrapper() {
  }

  @Override
  void onOpen(Session session, EndpointConfig endpointConfig) {
    def span = endpointConfig.getUserProperties().get(AgentSpan.class.getName()) as AgentSpan
    def endpoint = endpointConfig.getUserProperties().get(Endpoint.class.getName()) as Endpoint
    assert endpoint != null
    session.getUserProperties().put(Endpoint.class.getName(), endpoint)
    try (def ignored = span != null ? activateSpan(span) : null) {
      endpoint.onOpen(session, endpointConfig)
    }
  }

  @Override
  void onClose(Session session, CloseReason closeReason) {
    def endpoint = session.getUserProperties().get(Endpoint.class.getName()) as Endpoint
    assert endpoint != null
    endpoint.onClose(session, closeReason)
  }

  @Override
  void onError(Session session, Throwable thr) {
    def endpoint = session.getUserProperties().get(Endpoint.class.getName()) as Endpoint
    assert endpoint != null
    endpoint.onError(session, thr)
  }
}
