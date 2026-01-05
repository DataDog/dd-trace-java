import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.mongo.BsonScrubber34
import org.bson.BsonDocument
import org.bson.BsonDocumentReader

class BsonScrubber34Test extends InstrumentationSpecification {

  def "test BSON scrubber"() {
    setup:
    BsonDocument doc = BsonDocument.parse(input)

    when:
    BsonScrubber34 scrubber = new BsonScrubber34()
    scrubber.pipe(new BsonDocumentReader(doc))
    String resourceName = scrubber.getResourceName()

    then:
    resourceName == expected

    cleanup:
    scrubber.close()

    where:
    input                                                                                                                                                                                                                                                                                | expected
    "{ _id : { project_id : '\$project_id', date : '\$VehicleEntry.@Date' }, passed : { \$sum : { \$cond : [ { \$eq : [ '\$VehicleEntry.VehicleStatus', 'PASSED' ] }, 1, 0 ] } }, failed : { \$sum : { \$cond : [ { \$eq : [ '\$VehicleEntry.VehicleStatus', 'FAILED' ] }, 1, 0 ] } } }" | "{\"_id\": {\"project_id\": \$project_id, \"date\": \$VehicleEntry.@Date}, \"passed\": {\"\$sum\": {\"\$cond\": [{\"\$eq\": [\$VehicleEntry.VehicleStatus, \"?\"]}, \"?\", \"?\"]}}, \"failed\": {\"\$sum\": {\"\$cond\": [{\"\$eq\": [\$VehicleEntry.VehicleStatus, \"?\"]}, \"?\", \"?\"]}}}"
    "{\"update\": \"topics\", \"ordered\": true, \"writeConcern\": {\"w\": \"majority\"}, \"txnNumber\": \"?\", \"\$db\": \"database\", \"\$clusterTime\": {\"clusterTime\": \"?\", \"signature\": {\"hash\": \"?\", \"keyId\": \"?\"}}, \"lsid\": {\"id\": \"?\"}, \"updates\": \"?\"}" | "{\"update\": \"topics\", \"ordered\": true, \"writeConcern\": {\"w\": \"majority\"}, \"txnNumber\": \"?\", \"\$db\": \"database\", \"\$clusterTime\": {\"clusterTime\": \"?\", \"signature\": {\"hash\": \"?\", \"keyId\": \"?\"}}, \"lsid\": {\"id\": \"?\"}, \"updates\": \"?\"}"
    "{\"update\" : \"orders\", \"ordered\" : false, \"writeConcern\" : { \"w\" : \"majority\" }, \"updates\": [{ \"q\" : { \"_id\" : 1 }, \"u\" : { \"orderId\" : \"Account1\", \"qty\" : 10 } } ]}"                                                                                 | "{\"update\": \"orders\", \"ordered\": false, \"writeConcern\": {\"w\": \"majority\"}, \"updates\": []}"
    "{\"insert\" : \"stuff\", \"ordered\" : true, \"writeConcern\" : { \"w\" : 10 }, \"documents\": [{ \"_id\" : { \"s\" : 0, \"i\": \"DEADBEEF\" }, \"array\" : [0, \"foo\", {\"foo\": 10}], \"qty\" : 10 } ]}"                                                                      | "{\"insert\": \"stuff\", \"ordered\": true, \"writeConcern\": {\"w\": 10}, \"documents\": []}"
  }

  def "test BSON scrubber truncates long sequences"() {
    setup:
    // Create a document with an array containing 300 elements (more than the limit)
    def elements = (1..300).collect { "\"item$it\"" }.join(", ")
    def input = "{\"find\": \"collection\", \"filter\": {\"\$or\": [$elements]}}"
    BsonDocument doc = BsonDocument.parse(input)

    when:
    BsonScrubber34 scrubber = new BsonScrubber34()
    scrubber.pipe(new BsonDocumentReader(doc))
    String resourceName = scrubber.getResourceName()

    then:
    // The output should contain exactly 256 "?" elements (the first 256 get obfuscated)
    // and should still be valid JSON with a closing bracket
    resourceName.startsWith("{\"find\": \"collection\", \"filter\": {\"\$or\": [")
    resourceName.endsWith("]}}")
    // Count the number of obfuscated values - should be exactly 256
    resourceName.count("\"?\"") == 256

    cleanup:
    scrubber.close()
  }
}
