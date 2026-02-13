package com.datadog.profiling.utils.zstd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lazy-init holder for ASM-generated encoder instances. All generation is wrapped in try-catch —
 * falls back to null on failure (callers use Java baseline).
 */
final class AsmEncoders {
  private static final Logger log = LoggerFactory.getLogger(AsmEncoders.class);

  private static volatile MatchCopy matchCopy;

  private AsmEncoders() {}

  static MatchCopy getMatchCopy() {
    MatchCopy mc = matchCopy;
    if (mc == null) {
      synchronized (AsmEncoders.class) {
        mc = matchCopy;
        if (mc == null) {
          try {
            mc = MatchCopyGenerator.generate();
            matchCopy = mc;
          } catch (Throwable t) {
            log.debug("Failed to generate MatchCopy, using Java baseline", t);
          }
        }
      }
    }
    return mc;
  }
}
