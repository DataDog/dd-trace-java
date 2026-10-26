package datadog.trace.core;

import static datadog.trace.api.KnownTags.HTTP_ROUTE_ID;
import static datadog.trace.api.KnownTags.HTTP_STATUS_CODE_ID;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.SpanStorage;
import datadog.trace.api.TagMap;
import datadog.trace.common.writer.ListWriter;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A known tag's id and name address the same tag, in every {@link SpanStorage}. */
class SpanStorageTest extends DDCoreJavaSpecification {
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void storedByIdLoadsByName() {
    forEachStorage(
        storage -> {
          storage.store(HTTP_ROUTE_ID, "/users/{id}");

          assertEquals("/users/{id}", storage.load(HTTP_ROUTE));
          assertEquals("/users/{id}", storage.load(HTTP_ROUTE_ID));
        });
  }

  @Test
  void storedByNameLoadsById() {
    forEachStorage(
        storage -> {
          storage.store(HTTP_ROUTE, "/users/{id}");

          assertEquals("/users/{id}", storage.load(HTTP_ROUTE_ID));
        });
  }

  @Test
  void loadStringByIdConverts() {
    forEachStorage(
        storage -> {
          storage.store(HTTP_STATUS_CODE_ID, 404);

          assertEquals("404", storage.loadString(HTTP_STATUS_CODE_ID));
          assertEquals("404", storage.loadString(HTTP_STATUS));
        });
  }

  @Test
  void nullStoredByIdRemoves() {
    forEachStorage(
        storage -> {
          storage.store(HTTP_ROUTE, "/users/{id}");

          storage.store(HTTP_ROUTE_ID, null);

          assertNull(storage.load(HTTP_ROUTE));
          assertNull(storage.loadString(HTTP_ROUTE_ID));
        });
  }

  private void forEachStorage(Consumer<SpanStorage> check) {
    check.accept(TagMap.create());

    DDSpan span = (DDSpan) tracer.buildSpan("test", "span").start();
    try {
      check.accept(span.spanContext());
    } finally {
      span.finish();
    }
  }
}
