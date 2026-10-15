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
      ContextStore<AgentSpan, RouteState> routes) {
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
            new Part(frame.parent.path.length() - frame.path.length(), consumed, template));
      }
      return;
    }
    RouteState previous = routes.get(frame.span);
    Object requestPath = frame.span.getTag(KnownTags.URL_PATH_NAME);
    if (requestPath != null && requestPath.toString().equals(URIUtils.decode(frame.path))) {
      // A new alternative starts at the beginning of the request path.
    } else if (previous != null && previous.remaining.equals(frame.path)) {
      template = previous.template + template;
    } else {
      // A suffix or an unrelated matcher cannot safely supply the request's route.
      return;
    }
    routes.put(frame.span, new RouteState(template, rest));
    Object method = frame.span.getTag(KnownTags.HTTP_METHOD_OTEL_NAME);
    HTTP_RESOURCE_DECORATOR.withRoute(
        frame.span, method == null ? null : method.toString(), template, true);
  }

  public static final class Frame {
    final String path;
    final AgentSpan span;
    final Frame parent;
    final List<Part> parts = new ArrayList<>();

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
      for (Part part : parts) {
        if (part.offset < cursor || part.offset + part.length > consumed) {
          continue;
        }
        template.append(path, cursor, part.offset).append(part.template);
        cursor = part.offset + part.length;
      }
      return template.append(path, cursor, consumed).toString();
    }
  }

  public static final class Part {
    final int offset;
    final int length;
    final String template;

    Part(int offset, int length, String template) {
      this.offset = offset;
      this.length = length;
      this.template = template;
    }
  }

  public static final class RouteState {
    final String template;
    final String remaining;

    RouteState(String template, String remaining) {
      this.template = template;
      this.remaining = remaining;
    }
  }
}
