package datadog.trace.codecoverage;

import java.util.BitSet;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 1 stub sender that logs coverage summaries. Will be replaced by a real backend sender in
 * future phases.
 */
public final class LoggingCodeCoverageSender implements CodeCoverageSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingCodeCoverageSender.class);

  @Override
  public void send(Map<String, BitSet> coverage) {
    int totalLines = 0;
    for (BitSet lines : coverage.values()) {
      totalLines += lines.cardinality();
    }
    log.info("Code coverage collected: {} files, {} lines covered", coverage.size(), totalLines);
  }
}
