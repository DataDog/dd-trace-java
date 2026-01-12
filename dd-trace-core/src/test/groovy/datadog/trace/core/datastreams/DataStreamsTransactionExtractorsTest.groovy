package datadog.trace.core.datastreams

import datadog.trace.api.datastreams.DataStreamsTransactionExtractor
import datadog.trace.core.test.DDCoreSpecification

class DataStreamsTransactionExtractorsTest extends DDCoreSpecification {
  def "Deserialize from json"() {
    when:
    def list = DataStreamsTransactionExtractors.deserialize("""[
      {"name": "extractor", "type": "HTTP_OUT_HEADERS", "value": "transaction_id"},
      {"name": "second_extractor", "type": "HTTP_IN_HEADERS", "value": "transaction_id"}
    ]""")
    def extractors = list.getExtractors()
    then:
    extractors.size() == 2
    extractors[0].getName() == "extractor"
    extractors[0].getType() == DataStreamsTransactionExtractor.Type.HTTP_OUT_HEADERS
    extractors[0].getValue() == "transaction_id"
    extractors[1].getName() == "second_extractor"
    extractors[1].getType() == DataStreamsTransactionExtractor.Type.HTTP_IN_HEADERS
    extractors[1].getValue() == "transaction_id"
  }
}
