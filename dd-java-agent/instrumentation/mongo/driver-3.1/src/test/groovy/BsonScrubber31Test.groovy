import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.mongo.BsonScrubber31
import org.bson.BsonDocument
import org.bson.BsonDocumentReader

class BsonScrubber31Test extends InstrumentationSpecification {

  def "test BSON scrubber"() {
    setup:
    BsonDocument doc = BsonDocument.parse(input)

    when:
    BsonScrubber31 scrubber = new BsonScrubber31()
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
}
