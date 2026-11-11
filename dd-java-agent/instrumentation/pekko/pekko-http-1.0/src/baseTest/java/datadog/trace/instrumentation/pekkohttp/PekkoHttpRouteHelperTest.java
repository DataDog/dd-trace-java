package datadog.trace.instrumentation.pekkohttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.Context;
import datadog.context.ContextScope;
import org.apache.pekko.http.scaladsl.model.Uri;
import org.apache.pekko.http.scaladsl.server.PathMatcher;
import org.junit.jupiter.api.Test;

class PekkoHttpRouteHelperTest {
  private static Uri.Path path(String value) {
    return Uri.apply(value).path();
  }

  @Test
  void failedCompositeRollsBackItsSuccessfulLeaves() {
    PekkoHttpRouteState state = new PekkoHttpRouteState(path("/items/value"));
    state.matched(path("/items/value"), path("items/value"), "/");
    state.matched(path("items/value"), path("/value"), "items");
    state.matched(path("/value"), path("value"), "/");
    state.unmatched(path("/items/value"));
    assertNull(state.template());
    assertEquals(path("/items/value"), state.remaining);
  }

  @Test
  void rejectedAlternativeCannotChangeItsParent() {
    PekkoHttpRouteState parent = new PekkoHttpRouteState(path("/items/value"));
    parent.matched(path("/items/value"), path("/value"), "/items");
    PekkoHttpRouteState child = parent.fork();
    child.matched(path("/value"), path(""), "/{param}");
    assertEquals("/items", parent.template());
    assertEquals("/items/{param}", child.template());
    PekkoHttpRouteState fallback = parent.fork();
    parent.select(fallback);
    assertEquals("/items", parent.template());
  }

  @Test
  void completedAlternativePublishesItsOwnRoute() {
    PekkoHttpRouteState parent = new PekkoHttpRouteState(path("/items/value"));
    PekkoHttpRouteState child = parent.fork();
    child.matched(path("/items/value"), path(""), "/items/{param}");
    parent.select(child);
    assertEquals("/items/{param}", parent.template());
  }

  @Test
  void consumingMatchReplacesLookaheadWithoutChangingItsParent() {
    PekkoHttpRouteState parent = new PekkoHttpRouteState(path("/api/items/value"));
    parent.matched(path("/api/items/value"), path("/items/value"), "/api");
    parent.matched(path("/items/value"), path("/value"), "/{param}");
    PekkoHttpRouteState child = parent.fork();
    child.matched(path("/items/value"), path("items/value"), "/");
    child.matched(path("items/value"), path("/value"), "items");
    child.matched(path("/value"), path(""), "/{param}");
    assertEquals("/api/items/{param}", child.template());
    assertEquals("/api/{param}", parent.template());
  }

  @Test
  void parentCompositeDoesNotDuplicateLeafContributions() {
    PekkoHttpRouteState state = new PekkoHttpRouteState(path("/items"));
    state.matched(path("/items"), path("items"), "/");
    state.matched(path("items"), path(""), "items");
    PathMatcher.Matched<?> result = new PathMatcher.Matched<>(path(""), null, null);
    // No matcher metadata should be accessed for a remainder already recorded by the leaves.
    try (ContextScope ignored = state.storeIn(Context.root()).attach()) {
      PekkoHttpRouteHelper.matched(null, path("/items"), result, null);
    }
    assertEquals("/items", state.template());
  }
}
