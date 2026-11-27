package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PlayRouteParserCacheTest {

  @Test
  void sameRouteReturnsCachedInstance() {
    RouteTemplate first = PlayRouteParser.cached("/users/$id<[^/]+>");
    assertSame(first, PlayRouteParser.cached(new String("/users/$id<[^/]+>")));
    assertEquals("/users/{id}", first.staticRoute());
  }

  @Test
  void differentRoutesGiveDifferentTemplates() {
    RouteTemplate users = PlayRouteParser.cached("/users/$id<[^/]+>");
    RouteTemplate orders = PlayRouteParser.cached("/orders/$id<[^/]+>");
    assertNotSame(users, orders);
    assertEquals("/orders/{id}", orders.staticRoute());
  }

  @Test
  void nullEmptyAndMalformedAreNotExpressible() {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(null));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(""));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached("/a/$x<"));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached("$<>{"));
    assertEquals("/%7Bparam%7D", PlayRouteParser.cached("/{param}").staticRoute());
  }

  @Test
  void unparseableRouteIsCachedAsNotExpressible() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    AtomicInteger calls = new AtomicInteger();
    Function<String, RouteTemplate> producer =
        PlayRouteParser.safely(path -> {
          calls.incrementAndGet();
          throw new IllegalStateException("parser bug");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(cache, "/a", producer));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(cache, "/a", producer));
    assertEquals(1, calls.get());
    assertEquals(1, size(cache));
  }

  @Test
  void parserErrorIsNotCached() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    Function<String, RouteTemplate> failing =
        PlayRouteParser.safely(path -> {
          throw new StackOverflowError("transient");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(cache, "/a", failing));
    assertEquals(0, size(cache));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(cache, null, failing));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, PlayRouteParser.cached(cache, "", failing));
    assertEquals(0, size(cache));
  }

  private static int size(DDCache<String, RouteTemplate> cache) {
    AtomicInteger count = new AtomicInteger();
    cache.visit((k, v) -> count.incrementAndGet());
    return count.get();
  }
}
