package datadog.trace.core.scopemanager;

import io.opentracing.Scope;
import io.opentracing.Span;

// Intentionally package private.
interface DDScope extends Scope {
  @Override
  Span span();

  int depth();

  DDScope incrementReferences();
}
