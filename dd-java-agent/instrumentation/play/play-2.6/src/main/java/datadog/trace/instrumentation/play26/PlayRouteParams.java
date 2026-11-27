package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.ContextStores.getContextStore;
import static datadog.trace.bootstrap.ContextStores.getContextStoreId;

import datadog.trace.bootstrap.ContextStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import play.api.mvc.RequestHeader;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.util.Either;

/**
 * Path parameters bound by Play's router for the matched route ({@code RouteParams.path()}),
 * captured by {@link PlayRouteParamsInstrumentation} and keyed by the request target, which Play
 * carries over when it copies the request after routing.
 */
public final class PlayRouteParams {

  static final String REQUEST_TARGET = "play.api.mvc.request.RequestTarget";
  static final String BOUND_VALUES = "scala.collection.immutable.Map";

  private static final ContextStore<Object, Object> STORE =
      getContextStore(getContextStoreId(REQUEST_TARGET, BOUND_VALUES));

  private PlayRouteParams() {}

  /** Marker value of a parameter Play matched but failed to decode: present, value unknown. */
  static final String UNDECODED = "?";

  /**
   * Returns the path parameters bound by Play's router as a Java map, or null when none were
   * captured. A {@code Left} value (matched, decoding failed) maps to {@link #UNDECODED}.
   */
  public static Map<String, String> boundValues(
      final scala.collection.immutable.Map<String, Either<Throwable, String>> path) {
    if (path == null) {
      return null;
    }
    if (path.isEmpty()) {
      return Collections.emptyMap();
    }
    final Map<String, String> values = new HashMap<>((int) (path.size() / 0.75f) + 1);
    final Iterator<Tuple2<String, Either<Throwable, String>>> it = path.iterator();
    while (it.hasNext()) {
      final Tuple2<String, Either<Throwable, String>> entry = it.next();
      final Either<Throwable, String> value = entry._2();
      if (value == null) {
        continue;
      }
      if (value.isRight()) {
        values.put(entry._1(), value.right().get());
      } else {
        // the segment matched, so the part is present even if its value could not be decoded
        values.put(entry._1(), UNDECODED);
      }
    }
    return values;
  }

  /**
   * Path parameters ({@code RouteParams.path()}) captured for this request, or null when none were
   * captured.
   */
  @SuppressWarnings("unchecked")
  public static scala.collection.immutable.Map<String, Either<Throwable, String>> capturedFor(
      final RequestHeader request) {
    final Object values = STORE.get(request.target());
    return values instanceof scala.collection.immutable.Map
        ? (scala.collection.immutable.Map<String, Either<Throwable, String>>) values
        : null;
  }
}
