package datadog.trace.instrumentation.play24;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;
import play.api.mvc.Headers;
import play.api.mvc.Request;
import play.api.mvc.Request$;
import play.api.mvc.RequestHeader;
import scala.Function0;
import scala.collection.immutable.Map;
import scala.runtime.AbstractFunction0;

/**
 * The way to add tags to a request differ between Play 2.4 and 2.5, so to avoid duplicating the
 * whole instrumentation, the code and logic for adding a tag to a request has been encapsulated in
 * this helper class.
 */
public class RequestHelper {
  // This is the method for creating a new Request from a RequestHeader and a value
  private static final MethodHandle APPLY;
  // This is the normal Play 2.5 method for adding a tag to a RequestHeader
  private static final MethodHandle WITH_TAG;
  // Play 2.4 does not have a withTag method, so there we need to call the copy method on
  // the RequestHeader ourselves with an updated tags Map instead
  private static final MethodHandle COPY;

  static {
    MethodHandle withTag = null;
    MethodHandle apply = null;
    MethodHandle copy = null;
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    try {
      MethodType mt = MethodType.methodType(Request.class, RequestHeader.class, Object.class);
      apply = lookup.findVirtual(Request$.class, "apply", mt);
    } catch (final NoSuchMethodException | IllegalAccessException ignored) {
      // We never expect this to fail, and if it does then the play application itself is probably
      // misconfigured, so we will revert to not adding headers which is safe
    }
    // Only try to look up the withTag or copy method if we can use apply to create a tagged Request
    if (apply != null) {
      try {
        MethodType mt = MethodType.methodType(RequestHeader.class, String.class, String.class);
        withTag = lookup.findVirtual(RequestHeader.class, "withTag", mt);
      } catch (final NoSuchMethodException | IllegalAccessException ignored1) {
        // So we failed to look up withTag, then this is probably Play 2.4 and we should
        // try to look up the copy method instead
        try {
          Class<?> rh = RequestHeader.class;
          Class<?> m = Map.class;
          Class<?> s = String.class;
          Class<?> f = Function0.class;
          MethodType mt =
              MethodType.methodType(rh, long.class, m, s, s, s, s, m, Headers.class, f, f);
          copy = lookup.findVirtual(rh, "copy", mt);
        } catch (final NoSuchMethodException | IllegalAccessException ignored2) {
        }
      }
    }
    APPLY = apply;
    WITH_TAG = withTag;
    COPY = copy;
  }

  public static Request withTag(final Request request, final String key, final String value) {
    Request newRequest = request;
    if (APPLY != null) {
      try {
        RequestHeader newHeader = request;
        if (WITH_TAG != null) {
          newHeader = (RequestHeader) WITH_TAG.invokeExact((RequestHeader) request, key, value);
        } else if (COPY != null) {
          RequestHeader header = request;
          try {
            newHeader =
                (RequestHeader)
                    COPY.invokeExact(
                        header,
                        header.id(),
                        header.tags().updated(key, value),
                        header.uri(),
                        header.path(),
                        header.method(),
                        header.version(),
                        header.queryString(),
                        header.headers(),
                        (Function0) SFunction0.from(header::remoteAddress),
                        (Function0) SFunction0.from(header::secure));
          } catch (Throwable ignored1) {
          }
        }
        // Only return an updated Request if we manageed to add the tag to the RequesHeader
        if (newHeader != newRequest) {
          // This is calling the apply method on the Scala `object` `Request`
          newRequest = (Request) APPLY.invokeExact(Request$.MODULE$, newHeader, request.body());
        }
      } catch (Throwable ignored2) {
      }
    }
    return newRequest;
  }

  /**
   * Scala Function0 is not a Java Functional Interface in older Scala versions, so add a helper
   * class to bridge the gap.
   */
  public static class SFunction0<T> extends AbstractFunction0<T> {
    private final Supplier<T> supplier;

    public static <A> SFunction0<A> from(final Supplier<A> supplier) {
      return new SFunction0<>(supplier);
    }

    private SFunction0(final Supplier<T> supplier) {
      this.supplier = supplier;
    }

    @Override
    public T apply() {
      return supplier.get();
    }
  }
}
