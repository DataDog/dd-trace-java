package datadog.trace.instrumentation.opentelemetry14.context.propagation;

import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class helps to decode W3C tracestate header into a {@link TraceState} instance using OTel
 * API.
 */
public class TraceStateHelper {
  private static final int TRACESTATE_MAX_SIZE = 512;
  private static final char TRACESTATE_ENTRY_DELIMITER = ',';
  private static final char TRACESTATE_KEY_VALUE_DELIMITER = '=';

  private static final Function<String, TraceState> DECODER;
  private static final Function<TraceState, String> ENCODER;
  private static final Logger LOGGER = LoggerFactory.getLogger(TraceStateHelper.class);

  static {
    DECODER = lookForDecoder();
    ENCODER = lookForEncoder();
  }

  /**
   * Looks for a {@link TraceState} decoder function. Tries to use:
   *
   * <ul>
   *   <li>W3CTraceContextEncoding.decodeTraceState(String):TraceState static helper if available,
   *   <li>W3CTraceContextPropagator.extractTraceState(String):TraceState private method otherwise,
   *   <li>A default trace state provider if none of the above solution worked.
   * </ul>
   *
   * @return The {@link TraceState} decoder function
   */
  private static Function<String, TraceState> lookForDecoder() {
    Function<String, TraceState> decoder = header -> TraceState.getDefault();
    try {
      try {
        Class<?> encodingClass =
            Class.forName(
                "io.opentelemetry.api.trace.propagation.internal.W3CTraceContextEncoding",
                false,
                W3CTraceContextPropagator.class.getClassLoader());
        MethodHandle decodeTraceStateHandle =
            MethodHandles.lookup()
                .findStatic(
                    encodingClass,
                    "decodeTraceState",
                    MethodType.methodType(TraceState.class, String.class));
        decoder =
            header -> {
              try {
                return (TraceState) decodeTraceStateHandle.invokeExact(header);
              } catch (Throwable t) {
                return TraceState.getDefault();
              }
            };
      } catch (ClassNotFoundException e) {
        // The W3CTraceContextEncoding class is not available is older versions of OTel
        // Try to use W3CTraceContextPropagator.extractTraceState(String):String instead
        Class<W3CTraceContextPropagator> propagatorClass = W3CTraceContextPropagator.class;
        Method extractTraceStateMethod =
            propagatorClass.getDeclaredMethod("extractTraceState", String.class);
        extractTraceStateMethod.setAccessible(true);
        MethodHandle extractTraceStateHandle =
            MethodHandles.lookup()
                .unreflect(extractTraceStateMethod)
                .asType(MethodType.methodType(TraceState.class, String.class));
        decoder =
            header -> {
              try {
                return (TraceState) extractTraceStateHandle.invokeExact(header);
              } catch (Throwable t) {
                return TraceState.getDefault();
              }
            };
      }
    } catch (Throwable t) {
      // Failed to find a tracestate decoder
      LOGGER.debug("Unable to setup OpenTelemetry instrumentation tracestate decoder", t);
    }
    return decoder;
  }

  /**
   * Looks for a {@link TraceState} encoder function. Tries to use:
   *
   * <ul>
   *   <li>W3CTraceContextEncoding.encodeTraceStace(TraceStace):String static helper if available,
   *   <li>A basic vendor implementation if none of the above solution worked.
   * </ul>
   *
   * @return The {@link TraceState} decoder function
   */
  private static Function<TraceState, String> lookForEncoder() {
    Function<TraceState, String> encoder = tracestate -> "";
    try {
      try {
        Class<?> encodingClass =
            Class.forName(
                "io.opentelemetry.api.trace.propagation.internal.W3CTraceContextEncoding",
                false,
                W3CTraceContextPropagator.class.getClassLoader());
        MethodHandle encodeTraceStateHandle =
            MethodHandles.lookup()
                .findStatic(
                    encodingClass,
                    "encodeTraceState",
                    MethodType.methodType(String.class, TraceState.class));
        encoder =
            tracestate -> {
              try {
                return (String) encodeTraceStateHandle.invokeExact(tracestate);
              } catch (Throwable t) {
                return "";
              }
            };
      } catch (final ClassNotFoundException e) {
        encoder = TraceStateHelper::encodeTraceState;
      }
    } catch (Throwable t) {
      // Failed to find a tracestate encoder
      LOGGER.debug("Unable to setup OpenTelemetry instrumentation tracestate decoder", t);
    }
    return encoder;
  }

  // Inspired from W3CTraceContextEncoding.encodeTraceState only available in API later versions.
  private static String encodeTraceState(TraceState traceState) {
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

  public static TraceState decodeHeader(String header) {
    return DECODER.apply(header);
  }

  public static String encodeHeader(TraceState tracestate) {
    return ENCODER.apply(tracestate);
  }
}
