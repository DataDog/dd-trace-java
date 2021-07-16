package io.sqreen.testapp.sampleapp

import com.mongodb.BasicDBObject
import com.mongodb.MongoClient
import com.mongodb.WriteConcern
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.DeleteManyModel
import io.sqreen.testapp.imitation.VulnerableQuery
import org.bson.Document
import org.bson.RawBsonDocument
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/mongo')
class MongoController {

  @Autowired
  ApplicationContext applicationContext

  @RequestMapping
  String search(@RequestParam String name) {
    def query = BasicDBObject.parse(
      VulnerableQuery.build("{ \"name\": ", name, " }")
      )
    def list = collection.find(query)
    list.join("\n")
  }

  @RequestMapping(value = "/delete")
  String delete(@RequestParam String filter) {
    def bsonFilter = RawBsonDocument.parse("{ \"name\": $filter }")
    def model = new DeleteManyModel<Document>(bsonFilter)
    // unacknowledged, to test another hook point on < 3.6
    collection.withWriteConcern(new WriteConcern(0))
      .bulkWrite([model], new BulkWriteOptions().ordered(false))
  }

  private MongoCollection<Document> getCollection() {
    mongoClient.getDatabase('db').getCollection('people')
  }

  private MongoClient getMongoClient() {
    applicationContext.getBean('mongo')
  }
}
