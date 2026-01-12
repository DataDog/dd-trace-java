package datadog.trace.api.datastreams;

import java.util.List;

public interface DataStreamsTransactionTracker {
  /** trackTransaction used to emit "seen" even for transactions */
  void trackTransaction(String transactionId, String checkpointName);

  /** extractorsByType returns the list of extractors */
  List<DataStreamsTransactionExtractor> getTransactionExtractorsByType(
      DataStreamsTransactionExtractor.Type extractorType);
}
