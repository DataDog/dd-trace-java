package datadog.trace.core;

import static datadog.trace.api.KnownTags.HTTP_ROUTE_ID;
import static datadog.trace.api.KnownTags.HTTP_STATUS_CODE_ID;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_ROUTE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.TagMap;
import datadog.trace.api.TagStore;
import datadog.trace.common.writer.ListWriter;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A known tag's id and name address the same tag, in every {@link TagStore}. */
class TagStoreTest extends DDCoreJavaSpecification {
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
    forEachTagStore(
        tags -> {
          tags.store(HTTP_ROUTE_ID, "/users/{id}");

          assertEquals("/users/{id}", tags.load(HTTP_ROUTE));
          assertEquals("/users/{id}", tags.load(HTTP_ROUTE_ID));
        });
  }

  @Test
  void storedByNameLoadsById() {
    forEachTagStore(
        tags -> {
          tags.store(HTTP_ROUTE, "/users/{id}");

          assertEquals("/users/{id}", tags.load(HTTP_ROUTE_ID));
        });
  }

  @Test
  void loadStringByIdConverts() {
    forEachTagStore(
        tags -> {
          tags.store(HTTP_STATUS_CODE_ID, 404);

          assertEquals("404", tags.loadString(HTTP_STATUS_CODE_ID));
          assertEquals("404", tags.loadString(HTTP_STATUS));
        });
  }

  @Test
  void nullStoredByIdRemoves() {
    forEachTagStore(
        tags -> {
          tags.store(HTTP_ROUTE, "/users/{id}");

          tags.store(HTTP_ROUTE_ID, null);

          assertNull(tags.load(HTTP_ROUTE));
          assertNull(tags.loadString(HTTP_ROUTE_ID));
        });
  }

  private void forEachTagStore(Consumer<TagStore> check) {
    check.accept(TagMap.create());

    DDSpan span = (DDSpan) tracer.buildSpan("test", "span").start();
    try {
      check.accept(span.spanContext());
    } finally {
      span.finish();
    }
  }
}
