package datadog.trace.instrumentation.armeria.grpc.client;

import static datadog.trace.instrumentation.armeria.grpc.client.GrpcInjectAdapter.SETTER;
import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class GrpcInjectAdapterTest {

  @Test
  void carrierOverridesValuesForDuplicateKeys() {
    Metadata carrier = new Metadata();

    SETTER.set(carrier, "ot-baggage-foo", "v1");
    SETTER.set(carrier, "ot-baggage-foo", "v2");
    SETTER.set(carrier, "ot-baggage-bar", "v3");

    assertEquals(new HashSet<>(asList("ot-baggage-foo", "ot-baggage-bar")), carrier.keys());
    // overridden value wins, and replaces the earlier one
    assertEquals(singletonList("v2"), values(carrier, "ot-baggage-foo"));
    assertEquals(singletonList("v3"), values(carrier, "ot-baggage-bar"));
  }

  /** A name gRPC rejects is skipped, and the headers injected after it are not lost. */
  @TableTest({
    "scenario             | invalidKey      ",
    "space in name        | 'ot-baggage-a b'",
    "pseudo-header prefix | ':path'         ",
    "binary suffix        | 'ot-baggage-bin'"
  })
  void skipsHeaderNamesGrpcRejects(String invalidKey) {
    Metadata carrier = new Metadata();

    SETTER.set(carrier, invalidKey, "dropped");
    SETTER.set(carrier, "x-datadog-trace-id", "1");
    // a second time goes through the cached result for the rejected name
    SETTER.set(carrier, invalidKey, "dropped");

    assertEquals(singleton("x-datadog-trace-id"), carrier.keys());
    assertEquals(singletonList("1"), values(carrier, "x-datadog-trace-id"));
  }

  private static List<String> values(Metadata carrier, String name) {
    List<String> values = new ArrayList<>();
    for (String value : carrier.getAll(Metadata.Key.of(name, ASCII_STRING_MARSHALLER))) {
      values.add(value);
    }
    return values;
  }
}
