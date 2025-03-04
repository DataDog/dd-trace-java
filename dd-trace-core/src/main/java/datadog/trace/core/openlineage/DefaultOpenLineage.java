package datadog.trace.core.openlineage;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V1_OPENLINEAGE_ENDPOINT;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.experimental.OpenLineageEmitter;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.common.metrics.Sink;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultOpenLineage implements OpenLineageEmitter, EventListener {
  private static final Logger log = LoggerFactory.getLogger(DefaultOpenLineage.class);

  public Config config;
  public SharedCommunicationObjects sharedCommunicationObjects;
  private Sink agentSink;

  public DefaultOpenLineage(Config config, SharedCommunicationObjects sharedCommunicationObjects) {
    this.config = config;
    this.sharedCommunicationObjects = sharedCommunicationObjects;
    agentSink =
        new OkHttpSink(
            sharedCommunicationObjects.okHttpClient,
            sharedCommunicationObjects.agentUrl.toString(),
            V1_OPENLINEAGE_ENDPOINT,
            false,
            true,
            Collections.<String, String>emptyMap());
    agentSink.register(this);
  }

  @Override
  public void emitOpenLineage(String olEvent) {
    log.error("Emitting OpenLineage event", olEvent);
    ByteBuffer buffer = ByteBuffer.wrap(olEvent.getBytes(StandardCharsets.UTF_8));
    agentSink.accept(1, buffer);
    log.error("Emitted OpenLineage event");
  }

  @Override
  public void onEvent(EventType eventType, String message) {
    switch (eventType) {
      case DOWNGRADED:
        log.error("Agent downgrade was detected: {}", message);
        break;
      case BAD_PAYLOAD:
        log.error("bad payload sent to agent: {}", message);
        break;
      case ERROR:
        log.error("agent errored receiving payload: {}", message);
        break;
      case OK:
        log.error("agent received OK for event {}", message);
      default:
    }
  }
}
