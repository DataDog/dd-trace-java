package datadog.trace.api.experimental;

import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;

/** An interface representing the context carrier. Typically, message headers. */
public interface DataStreamsContextCarrier {
  /**
   * @return A set of key value pairs, such as message headers.
   */
  Set<Entry<String, Object>> entries();

  /**
   * @param key parameter to be set
   * @param value to be set
   */
  void set(String key, String value);

  final class NoOp implements DataStreamsContextCarrier {
    public static final DataStreamsContextCarrier INSTANCE = new NoOp();

    private NoOp() {}

    @Override
    public Set<Entry<String, Object>> entries() {
      return Collections.emptySet();
    }

    @Override
    public void set(String key, String value) {}
  }
}
