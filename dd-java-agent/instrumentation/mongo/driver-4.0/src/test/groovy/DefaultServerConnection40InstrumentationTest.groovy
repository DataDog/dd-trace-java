import static datadog.trace.agent.test.utils.TraceUtils.basicSpan

import com.mongodb.ConnectionString
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.MongoClientSettings
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import datadog.trace.api.config.TraceInstrumentationConfig
import org.bson.BsonDocument
import spock.lang.Shared
import static datadog.trace.api.Config.DBM_PROPAGATION_MODE_FULL

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class DefaultServerConnection40InstrumentationTest extends MongoBaseTest {
  @Shared
  MongoClient client

  @Shared
  TestCommandListener commandListener = new TestCommandListener()

  static class TestCommandListener implements CommandListener {
    final List<BsonDocument> commands = []

    @Override
    void commandStarted(CommandStartedEvent event) {
      commands.add(event.getCommand())
    }
  }

  def setup() throws Exception {
    def connectionString = "mongodb://${mongoDbContainer.getHost()}:$port/?appname=some-description"
    client = MongoClients.create(
      MongoClientSettings.builder()
      .applyConnectionString(new ConnectionString(connectionString))
      .addCommandListener(commandListener)
      .build())
  }

  def cleanup() throws Exception {
    client?.close()
    commandListener.commands.clear()
    client = null
  }
}

abstract class DefaultServerConnection40InstrumentationEnabledTest extends DefaultServerConnection40InstrumentationTest {
  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.DB_DBM_PROPAGATION_MODE_MODE, DBM_PROPAGATION_MODE_FULL)
  }

  def "test command comment injection"() {
    setup:
    def collectionName = randomCollectionName()
    def db = client.getDatabase(databaseName)

    when:
    runUnderTrace("parent") {
      db.createCollection(collectionName)
    }

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0,"parent")
        mongoSpan(it, 1, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\",\"comment\":\"?\"}", false, "some-description", span(0), true)
      }
    }

    // Verify command was captured and contains DBM comment
    commandListener.commands.size() > 0
    def createCommand = commandListener.commands.find { it.containsKey("create") }
    createCommand != null

    // Verify the comment contains expected DBM fields
    def comment = createCommand.get("comment")
    comment != null
    def commentStr = comment.asString().getValue()
    commentStr.contains("ddps='")
    commentStr.contains("dddb='database'")
    commentStr.contains("ddh='")
    commentStr.contains("traceparent='")
  }
}

abstract class DefaultServerConnection40InstrumentationDisabledTest extends DefaultServerConnection40InstrumentationTest {
  def "test command comment not injected when disabled"() {
    setup:
    injectSysConfig("dd.integration.mongo.dbm_propagation.enabled", "false")
    def collectionName = randomCollectionName()
    def db = client.getDatabase(databaseName)

    when:
    db.createCollection(collectionName)

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", "{\"create\":\"$collectionName\",\"capped\":\"?\"}")
      }
    }

    // Verify command was captured but has no comment
    commandListener.commands.size() > 0
    def createCommand = commandListener.commands.find { it.containsKey("create") }
    createCommand != null
    !createCommand.containsKey("comment")
  }
}

// Test class with DBM propagation enabled by default
class DefaultServerConnection40InstrumentationEnabledV0ForkedTest extends DefaultServerConnection40InstrumentationEnabledTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return V0_SERVICE
  }

  @Override
  String operation() {
    return V0_OPERATION
  }

  @Override
  String dbType() {
    return V0_DB_TYPE
  }
}

// Test class with service name mapping
class DefaultServerConnection40InstrumentationEnabledV1ForkedTest extends DefaultServerConnection40InstrumentationEnabledTest {
  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return V1_SERVICE
  }

  @Override
  String operation() {
    return V1_OPERATION
  }

  @Override
  String dbType() {
    return V1_DB_TYPE
  }
}

// Test class with DBM propagation enabled by default
class DefaultServerConnection40InstrumentationDisabledV0Test extends DefaultServerConnection40InstrumentationDisabledTest {
  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return V0_SERVICE
  }

  @Override
  String operation() {
    return V0_OPERATION
  }

  @Override
  String dbType() {
    return V0_DB_TYPE
  }
}

// Test class with service name mapping
class DefaultServerConnection40InstrumentationDisabledV1ForkedTest extends DefaultServerConnection40InstrumentationDisabledTest {
  @Override
  int version() {
    return 1
  }

  @Override
  String service() {
    return V1_SERVICE
  }

  @Override
  String operation() {
    return V1_OPERATION
  }

  @Override
  String dbType() {
    return V1_DB_TYPE
  }
}
