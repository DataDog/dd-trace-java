package datadog.trace.test.agent.decoder;

import java.util.List;

public interface DecodedMessage {
  List<DecodedTrace> getTraces();
}
