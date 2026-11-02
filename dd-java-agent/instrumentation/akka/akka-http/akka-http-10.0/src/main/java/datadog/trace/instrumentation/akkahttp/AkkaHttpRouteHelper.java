package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.PathMatcher;
import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import java.util.ArrayList;
import java.util.List;
import scala.Product;

/**
 * Builds templates from matched literals and extracted parameters, retaining no parameter values.
 */
public final class AkkaHttpRouteHelper {
  private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

  private AkkaHttpRouteHelper() {}

  public static Frame enter(Uri.Path path) {
    AgentSpan active = activeSpan();
    if (active == null) {
      return null;
    }
    AgentSpan root = active.getLocalRootSpan();
    if (root == null || root.getTag(KnownTags.URL_PATH_NAME) == null) {
      return null;
    }
    Frame frame = new Frame(path.toString(), root, CURRENT.get());
    CURRENT.set(frame);
    return frame;
  }

  public static void exit(
      Frame frame,
      PathMatcher.Matching<?> matching,
      Throwable throwable,
      ContextStore<AgentSpan, Route> routes) {
    if (frame == null) {
      return;
    }
    if (frame.parent == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(frame.parent);
    }
    if (throwable != null || !(matching instanceof PathMatcher.Matched)) {
      return;
    }
    PathMatcher.Matched<?> matched = (PathMatcher.Matched<?>) matching;
    String rest = matched.pathRest().toString();
    int consumed = frame.path.length() - rest.length();
    if (consumed <= 0) {
      return;
    }
    String template = frame.template(consumed, matched.extractions());
    if (frame.parent != null) {
      if (frame.parent.path.endsWith(frame.path)) {
        frame.parent.parts.add(
            new Route(frame.parent.path.length() - frame.path.length(), rest, template));
      }
      return;
    }
    Object requestPath = frame.span.getTag(KnownTags.URL_PATH_NAME);
    if (requestPath == null || !requestPath.toString().equals(URIUtils.decode(frame.path))) {
      Route previous = routes.get(frame.span);
      if (previous == null || !previous.remaining.equals(frame.path)) {
        return;
      }
      template = previous.template + template;
    }
    routes.put(frame.span, new Route(0, rest, template));
    Object method = frame.span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME);
    HTTP_RESOURCE_DECORATOR.withRoute(
        frame.span, method == null ? null : method.toString(), template, true);
  }

  public static final class Frame {
    final String path;
    final AgentSpan span;
    final Frame parent;
    final List<Route> parts = new ArrayList<>();

    Frame(String path, AgentSpan span, Frame parent) {
      this.path = path;
      this.span = span;
      this.parent = parent;
    }

    String template(int consumed, Object extractions) {
      if (parts.isEmpty()) {
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

  public static final class Route {
    final int offset;
    final String remaining;
    final String template;

    Route(int offset, String remaining, String template) {
      this.offset = offset;
      this.remaining = remaining;
      this.template = template;
    }
  }
}
