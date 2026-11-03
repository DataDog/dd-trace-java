package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.PathMatcher;
import datadog.trace.api.Config;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.instrumentation.akkahttp.AkkaHttpRouteState.Route;
import java.util.ArrayList;
import java.util.List;
import scala.Product;

/**
 * Builds templates from matched literals and extracted parameters, retaining no parameter values.
 */
public final class AkkaHttpRouteHelper {
  private static final boolean RAW_RESOURCE = Config.get().isHttpServerRawResource();
  private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

  private AkkaHttpRouteHelper() {}

  public static Frame enter(Uri.Path path) {
    Frame parent = CURRENT.get();
    if (parent != null) {
      // nested matchers share the root span already checked by the outermost frame
      Frame frame = new Frame(path, parent.span, parent.state, parent);
      CURRENT.set(frame);
      return frame;
    }
    AkkaHttpRouteState state = AkkaHttpRouteState.current();
    if (state == null) {
      return null;
    }
    AgentSpan active = activeSpan();
    if (active == null) {
      return null;
    }
    AgentSpan root = active.getLocalRootSpan();
    if (root == null || root.getTag(KnownTags.URL_PATH_NAME) == null) {
      return null;
    }
    Frame frame = new Frame(path, root, state, null);
    CURRENT.set(frame);
    return frame;
  }

  public static void exit(Frame frame, PathMatcher.Matching<?> matching, Throwable throwable) {
    if (frame == null) {
      return;
    }
    boolean isMatch = throwable == null && matching instanceof PathMatcher.Matched;
    if (frame.parent == null) {
      CURRENT.remove();
      frame.state.routeMatched = isMatch;
    } else {
      CURRENT.set(frame.parent);
    }
    if (!isMatch) {
      return;
    }
    PathMatcher.Matched<?> matched = (PathMatcher.Matched<?>) matching;
    String rest = matched.pathRest().toString();
    String path = frame.path();
    int consumed = path.length() - rest.length();
    if (consumed <= 0) {
      return;
    }
    String template = frame.template(consumed, matched.extractions());
    if (frame.parent != null) {
      String parentPath = frame.parent.path();
      if (parentPath.endsWith(path)) {
        if (frame.parent.parts == null) {
          frame.parent.parts = new ArrayList<>();
        }
        frame.parent.parts.add(new Route(parentPath.length() - path.length(), rest, template));
      }
      return;
    }
    Route previous = null;
    Object requestPath = frame.span.getTag(KnownTags.URL_PATH_NAME);
    if (requestPath == null
        || !requestPath.toString().equals(RAW_RESOURCE ? path : URIUtils.decode(path))) {
      previous = frame.state.route;
      // A rejected route can retry an earlier unmatched path in another alternative.
      while (previous != null && !previous.remaining.equals(path)) {
        previous = previous.previous;
      }
      if (previous == null) {
        return;
      }
      template = previous.template + template;
    }
    frame.state.route = new Route(0, rest, template, previous);
    Object method = frame.span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME);
    HTTP_RESOURCE_DECORATOR.withRoute(
        frame.span, method == null ? null : method.toString(), template, true);
  }

  public static final class Frame {
    final Uri.Path uriPath;
    final AgentSpan span;
    final AkkaHttpRouteState state;
    final Frame parent;
    List<Route> parts;
    private String path;

    Frame(Uri.Path uriPath, AgentSpan span, AkkaHttpRouteState state, Frame parent) {
      this.uriPath = uriPath;
      this.span = span;
      this.state = state;
      this.parent = parent;
    }

    /** Renders the path on demand, as most match attempts fail and never need it. */
    String path() {
      if (path == null) {
        path = uriPath.toString();
      }
      return path;
    }

    String template(int consumed, Object extractions) {
      String path = path();
      if (parts == null) {
        if (extractions instanceof Product && ((Product) extractions).productArity() > 0) {
          return path.charAt(0) == '/' ? "/{param}" : "{param}";
        }
        return path.substring(0, consumed);
      }
      StringBuilder template = new StringBuilder();
      int cursor = 0;
      for (Route part : parts) {
        int end = path.length() - part.remaining.length();
        if (part.offset < cursor || end > consumed) {
          continue;
        }
        template.append(path, cursor, part.offset).append(part.template);
        cursor = end;
      }
      return template.append(path, cursor, consumed).toString();
    }
  }
}
