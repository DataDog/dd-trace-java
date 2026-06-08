package datadog.trace.api.datastreams;

/**
 * Bridge class to allow tests to access package-private method exposed by the {@code
 * TransactionInfo}
 */
public class TransactionInfoTestBridge {
  public static void resetCache() {
    TransactionInfo.resetCache();
  }
}
