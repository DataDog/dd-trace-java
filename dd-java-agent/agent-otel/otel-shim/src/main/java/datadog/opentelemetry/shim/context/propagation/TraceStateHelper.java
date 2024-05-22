package datadog.opentelemetry.shim.context.propagation;

import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class helps to decode W3C tracestate header into a {@link TraceState} instance. */
public final class TraceStateHelper {
  private static final int TRACESTATE_MAX_SIZE = 512;
  private static final char TRACESTATE_ENTRY_DELIMITER = ',';
  private static final char TRACESTATE_KEY_VALUE_DELIMITER = '=';
  private static final Pattern TRACESTATE_ENTRY_DELIMITER_SPLIT_PATTERN =
      Pattern.compile("[ \t]*,[ \t]*");
  private static final Logger LOGGER = LoggerFactory.getLogger(TraceStateHelper.class);

  private TraceStateHelper() {}

  // Inspired from W3CTraceContextEncoding.decodeTraceState only available in API later versions.
  public static TraceState decodeHeader(String header) {
    TraceStateBuilder traceStateBuilder = TraceState.builder();
    String[] listMembers = TRACESTATE_ENTRY_DELIMITER_SPLIT_PATTERN.split(header);
    if (listMembers.length > 32) {
      LOGGER.warn("TraceState has too many elements: {}", header);
      return TraceState.getDefault();
    }

    for (int i = listMembers.length - 1; i >= 0; --i) {
      String listMember = listMembers[i];
      int index = listMember.indexOf(61);
      if (index == -1) {
        LOGGER.warn("Invalid TraceState list-member format: {}", listMember);
        return TraceState.getDefault();
      }
      traceStateBuilder.put(listMember.substring(0, index), listMember.substring(index + 1));
    }

    TraceState traceState = traceStateBuilder.build();
    if (traceState.size() != listMembers.length) {
      return TraceState.getDefault();
    } else {
      return traceState;
    }
  }

  // Inspired from W3CTraceContextEncoding.encodeTraceState only available in API later versions.
  public static String encodeHeader(TraceState traceState) {
    if (traceState.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder(TRACESTATE_MAX_SIZE);
    traceState.forEach(
        (key, value) -> {
          if (builder.length() != 0) {
            builder.append(TRACESTATE_ENTRY_DELIMITER);
          }
          builder.append(key).append(TRACESTATE_KEY_VALUE_DELIMITER).append(value);
        });
    return builder.toString();
  }
}
