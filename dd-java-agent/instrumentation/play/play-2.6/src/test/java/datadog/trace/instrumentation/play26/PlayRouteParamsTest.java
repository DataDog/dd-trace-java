package datadog.trace.instrumentation.play26;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import scala.Tuple2;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

class PlayRouteParamsTest {

  @Test
  void undecodedValueIsPresent() {
    scala.collection.immutable.Map<String, Either<Throwable, String>> path =
        scalaMap()
            .$plus(new Tuple2<>("id", right("42")))
            .$plus(new Tuple2<>("rest", right("")))
            .$plus(new Tuple2<>("bad", left(new IllegalArgumentException("bad"))));

    Map<String, String> expected = new HashMap<>();
    expected.put("id", "42");
    expected.put("rest", "");
    // matched but not decoded: present with a marker value
    expected.put("bad", PlayRouteParams.UNDECODED);
    assertEquals(expected, PlayRouteParams.boundValues(path));
  }

  @Test
  void singleValue() {
    assertEquals(
        singletonMap("path", "a%20b"),
        PlayRouteParams.boundValues(scalaMap().$plus(new Tuple2<>("path", right("a%20b")))));
  }

  @Test
  void emptyOrNullPath() {
    assertEquals(emptyMap(), PlayRouteParams.boundValues(scalaMap()));
    // not captured: unknown, not "no values"
    assertNull(PlayRouteParams.boundValues(null));
  }

  private static Either<Throwable, String> right(String value) {
    return new Right<>(value);
  }

  private static Either<Throwable, String> left(Throwable error) {
    return new Left<>(error);
  }

  private static scala.collection.immutable.Map<String, Either<Throwable, String>> scalaMap() {
    return scala.collection.immutable.Map$.MODULE$.empty();
  }
}
