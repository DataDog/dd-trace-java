package datadog.trace.instrumentation.pekkohttp;

import datadog.trace.bootstrap.ContextStore;
import org.apache.pekko.http.scaladsl.model.Uri;
import org.apache.pekko.http.scaladsl.server.PathMatcher;
import org.apache.pekko.http.scaladsl.server.PathMatchers;

/** Records successful leaf matches without allocating frames for matcher invocations. */
public final class PekkoHttpRouteHelper {
  private PekkoHttpRouteHelper() {}

  public static void matched(
      PathMatcher<?> matcher,
      Uri.Path before,
      PathMatcher.Matching<?> result,
      ContextStore<PathMatcher, String> prefixes) {
    PekkoHttpRouteState state = PekkoHttpRouteState.current();
    if (state == null || state.remaining == null) {
      return;
    }
    if (!(result instanceof PathMatcher.Matched)) {
      state.unmatched(before);
      return;
    }
    PathMatcher.Matched<?> matched = (PathMatcher.Matched<?>) result;
    Uri.Path after = matched.pathRest();
    if (before.equals(after)) {
      return;
    }
    // Most composed matchers finish at the remainder already recorded by their leaves.
    if (after.equals(state.remaining)) {
      return;
    }
    String literal = prefixes.get(matcher);
    if (literal == null) {
      if (matcher.getClass() == PathMatchers.Slash$.class) {
        literal = "/";
      } else {
        literal = before.startsWithSlash() ? "/{param}" : "{param}";
      }
    }
    state.matched(before, after, literal);
  }
}
