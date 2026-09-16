package datadog.trace.test.agent.decoder;

import java.util.List;
import java.util.Map;

public interface DecodedSpan {
  String getService();

  String getName();

  String getResource();

  /**
   * Returns the span 64-bit trace identifier, dropping high-order bits if present.
   *
   * @return The span 64-bit trace identifier.
   */
  long getTraceId();

  long getSpanId();

  long getParentId();

  long getStart();

  long getDuration();

  int getError();

  Map<String, String> getMeta();

  Map<String, Object> getMetaStruct();

  Map<String, Number> getMetrics();

  String getType();

  /**
   * Returns the links.
   *
   * @return The span links, empty when the span carries none.
   */
  List<DecodedSpanLink> getLinks();
}
