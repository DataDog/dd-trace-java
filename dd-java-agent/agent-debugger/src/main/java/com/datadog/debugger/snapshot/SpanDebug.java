package com.datadog.debugger.snapshot;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags;

public final class SpanDebug {
  // Granularity of debugging information we want to capture
  // enums instead?
  /** no debug information */
  public static final int NONE = 0;
  /** source code locations (just the span origin frame) */
  public static final int ORIGIN_FRAME_ONLY = 1;
  /** source code locations (full stack trace) */
  public static final int ALL_FRAMES = 2;
  /** variable values (request/response bodies, enriched dynamic logs) */
  public static final int CAPTURE_ORIGIN_FRAMES = 4;
  /** capture exception replay information */
  public static final int EXCEPTION_REPLAY = 8;
  /** capture all user probes */
  public static final int CAPTURE_ALL_PROBES = 16;

  public static boolean isSpanDebugEnabled(AgentSpan span, int... levels) {
    if (span.context() instanceof DDSpanContext) {
      int debug = getDebugLevel(span);

      for (int level : levels) {
        if ((debug & level) == level) {
          return true;
        }
      }
    }

    return false;
  }

  private static int getDebugLevel(AgentSpan span) {
    PropagationTags tags = ((DDSpanContext) span.context()).getPropagationTags();
    return tags.getDebugPropagation();
  }

  public static void enableDebug(AgentSpan span, int... levels) {
    if (span.context() instanceof DDSpanContext && levels.length != 0) {
      DDSpanContext context = (DDSpanContext) span.context();
      PropagationTags tags = context.getPropagationTags();
      int debug = 0;

      for (int level : levels) {
        debug |= level;
      }

      tags.updateDebugPropagation(debug);
    }
  }
}
