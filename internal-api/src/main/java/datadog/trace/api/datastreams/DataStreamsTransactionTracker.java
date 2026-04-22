package datadog.trace.api.datastreams;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface DataStreamsTransactionTracker {
  interface TransactionSourceReader {
    String readHeader(Object source, String headerName);
  }

  /** trackTransaction used to emit "seen" event for transactions */
  void trackTransaction(String transactionId, String checkpointName);

  /**
   * trackTransaction which tries to extract / track transactions info using extractors of
   * extractorType from the provided source using source reader
   */
  void trackTransaction(
      AgentSpan span,
      DataStreamsTransactionExtractor.Type extractorType,
      Object source,
      TransactionSourceReader sourceReader);
}
