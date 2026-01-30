package datadog.trace.logging.intake

import com.squareup.moshi.Moshi
import datadog.communication.BackendApi
import datadog.communication.util.IOThrowingFunction
import datadog.communication.util.IOUtils
import datadog.http.client.HttpRequestBody
import datadog.http.client.HttpRequestListener
import java.util.zip.GZIPInputStream
import javax.annotation.Nullable
import okio.Buffer
import spock.lang.Specification

class LogsDispatcherTest extends Specification {

  def "test messages that are too large are dropped"() {
    setup:
    def api = new DummyBackendApi()
    def maxMessageBytes = 20
    def dispatcher = new LogsDispatcher(api, LogsDispatcher.MAX_BATCH_RECORDS, LogsDispatcher.MAX_BATCH_BYTES, maxMessageBytes)

    when:
    dispatcher.dispatch([["message": "a"], ["message": "abcdefghijklmnop"]])

    then:
    1 == api.messages.size()
    "a" == api.messages.poll()["message"]
  }

  def "batches that exceed max number of records are split into multiple chunks"() {
    setup:
    def api = new DummyBackendApi()
    def maxBatchRecords = 2
    def dispatcher = new LogsDispatcher(api, maxBatchRecords, LogsDispatcher.MAX_BATCH_BYTES, LogsDispatcher.MAX_MESSAGE_BYTES)

    when:
    dispatcher.dispatch([["message": "a"], ["message": "b"], ["message": "c"], ["message": "d"], ["message": "e"]])

    then:
    3 == api.requestsReceived
    5 == api.messages.size()
    "a" == api.messages.poll()["message"]
    "b" == api.messages.poll()["message"]
    "c" == api.messages.poll()["message"]
    "d" == api.messages.poll()["message"]
    "e" == api.messages.poll()["message"]
  }

  def "batches that exceed max number of bytes are split into multiple chunks"() {
    setup:
    def api = new DummyBackendApi()
    def maxBatchBytes = 40
    def dispatcher = new LogsDispatcher(api, LogsDispatcher.MAX_BATCH_RECORDS, maxBatchBytes, LogsDispatcher.MAX_MESSAGE_BYTES)

    when:
    dispatcher.dispatch([["message": "a"], ["message": "b"], ["message": "c"], ["message": "d"], ["message": "e"]])

    then:
    3 == api.requestsReceived
    5 == api.messages.size()
    "a" == api.messages.poll()["message"]
    "b" == api.messages.poll()["message"]
    "c" == api.messages.poll()["message"]
    "d" == api.messages.poll()["message"]
    "e" == api.messages.poll()["message"]
  }

  private static final class DummyBackendApi implements BackendApi {
    private final listJsonAdapter = new Moshi.Builder().build().adapter(List)
    private final Queue<Map<String, Object>> messages = new ArrayDeque<>()
    private int requestsReceived = 0

    @Override
    <T> T post(String uri, String contentType, HttpRequestBody requestBody, IOThrowingFunction<InputStream, T> responseParser, @Nullable HttpRequestListener requestListener, boolean requestCompression) throws IOException {
      if (!requestCompression) {
        throw new AssertionError((Object) "Expected request to be compressed")
      }

      requestsReceived++

      def buffer = new Buffer()
      requestBody.writeTo(buffer)
      try (def is = new GZIPInputStream(buffer.inputStream())) {
        def json = IOUtils.readFully(is)
        messages.addAll(listJsonAdapter.fromJson(json))
      }
      return null
    }
  }
}
