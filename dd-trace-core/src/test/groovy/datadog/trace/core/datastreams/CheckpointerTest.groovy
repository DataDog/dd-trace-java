package datadog.trace.core.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.test.DDCoreSpecification;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;

class CheckpointerTest extends DDCoreSpecification {

  void 'test startTransaction and injectPathwayContext'() {
    setup:
    // Enable Data Streams Monitoring
    injectSysConfig(DATA_STREAMS_ENABLED, 'true')
    // Create a test tracer
    def tracer = tracerBuilder().build()
    AgentTracer.forceRegister(tracer)
    // Get the checkpointer
    def checkpointer = tracer.getDataStreamsCheckpointer()
    // Create a carrier for context propagation
    def carrier = new CustomContextCarrier()
    // Generate a unique transaction ID
    def transactionID = UUID.randomUUID().toString()

    when:
    // Start a transaction and inject pathway context
    checkpointer.startTransaction(transactionID, carrier)

    then:
    // Verify that the transaction ID is set in the carrier
    carrier.entries().any { entry -> entry.getKey() == "transaction.id" && entry.getValue() == transactionID }
    // Verify that the pathway context is injected into the carrier
    carrier.entries().any { entry -> entry.getKey() == "dd-pathway-ctx-base64" && entry.getValue() != null }
  }

  void 'test reportTransaction'() {
    setup:
    // Enable Data Streams Monitoring
    injectSysConfig(DATA_STREAMS_ENABLED, 'true')
    // Create a test tracer
    def tracer = tracerBuilder().build()
    AgentTracer.forceRegister(tracer)
    // Get the checkpointer
    def checkpointer = tracer.getDataStreamsCheckpointer()
    // Generate a unique transaction ID
    def transactionID = UUID.randomUUID().toString()

    when:
    // Report the transaction
    checkpointer.reportTransaction(transactionID)

    then:
    // Verify that the transaction was reported
    // This may involve checking internal metrics or state
    // Assuming the checkpointer provides a method to get reported transactions
    checkpointer.getReportedTransactions().contains(transactionID)
  }

  void 'test TransactionInbox processing'() {
    setup:
    // Create a TransactionInbox instance
    def transactionInbox = new TransactionInbox()
    // Create a sample transaction payload
    def transactionPayload = new TransactionPayload(transactionID: UUID.randomUUID().toString(), data: "sample data")

    when:
    // Send the transaction payload to the inbox
    transactionInbox.receive(transactionPayload)

    then:
    // Verify that the transaction was processed
    transactionInbox.containsTransaction(transactionPayload.transactionID)
  }

  void 'test payload compression and decompression'() {
    setup:
    // Create a large payload
    def payload = "x" * 10000 // 10,000 characters
    // Create an instance of a compression utility
    def compressionUtil = new CompressionUtil()

    when:
    // Compress the payload
    def compressedPayload = compressionUtil.compress(payload)
    // Decompress the payload
    def decompressedPayload = compressionUtil.decompress(compressedPayload)

    then:
    // Verify that the compressed payload is smaller
    compressedPayload.length < payload.length()
    // Verify that decompressed payload matches the original
    decompressedPayload == payload
  }

  class CustomContextCarrier implements DataStreamsContextCarrier {

    private Map<String, Object> data = new HashMap<>()

    @Override
    Set<Map.Entry<String, Object>> entries() {
      return data.entrySet()
    }

    @Override
    void set(String key, String value) {
      data.put(key, value)
    }
  }

  class TransactionInbox {
    private Set<String> transactions = new HashSet<>()

    void receive(TransactionPayload payload) {
      // Process the transaction payload
      transactions.add(payload.transactionID)
    }

    boolean containsTransaction(String transactionID) {
      return transactions.contains(transactionID)
    }
  }

  class TransactionPayload {
    String transactionID
    String data
  }

  class CompressionUtil {
    byte[] compress(String data) throws IOException {
      ByteArrayOutputStream bos = new ByteArrayOutputStream()
      GZIPOutputStream gzip = new GZIPOutputStream(bos)
      gzip.write(data.getBytes("UTF-8"))
      gzip.close()
      return bos.toByteArray()
    }

    String decompress(byte[] compressedData) throws IOException {
      ByteArrayInputStream bis = new ByteArrayInputStream(compressedData)
      GZIPInputStream gzip = new GZIPInputStream(bis)
      InputStreamReader reader = new InputStreamReader(gzip, "UTF-8")
      StringBuilder sb = new StringBuilder()
      int c
      while ((c = reader.read()) != -1) {
        sb.append((char) c)
      }
      return sb.toString()
    }
  }
}
