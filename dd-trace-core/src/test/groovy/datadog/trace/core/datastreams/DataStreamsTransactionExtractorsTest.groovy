package datadog.trace.core.datastreams

import datadog.trace.core.test.DDCoreSpecification

class DataStreamsTransactionExtractorsTest extends DDCoreSpecification {
  def "Deserialize from json"() {
    when:
    def list = DataStreamsTransactionExtractors.deserialize("""[
      {"name": "extractor", "type": "http_request_header", "value": "transaction_id"},
      {"name": "second_extractor", "type": "http_response_header", "value": "transaction_id"}
    ]""")
    def extractors = list.getExtractors()
    then:
    extractors.size() == 2
    extractors[0].getName() == "extractor"
    extractors[0].getType() == "http_request_header"
    extractors[0].getValue() == "transaction_id"
    extractors[1].getName() == "second_extractor"
    extractors[1].getType() == "http_response_header"
    extractors[1].getValue() == "transaction_id"
  }
}
