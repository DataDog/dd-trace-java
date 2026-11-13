package datadog.trace.instrumentation.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.ConnectionString;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.client.MongoClient;
import com.mongodb.async.client.MongoClientSettings;
import com.mongodb.async.client.MongoClients;
import com.mongodb.async.client.MongoDatabase;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.propagation.W3CTraceParent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

abstract class AsyncMongoDbmTest extends AbstractInstrumentationTest {
  private static MongoDBContainer mongo;

  @BeforeAll
  static void startMongo() {
    mongo =
        new MongoDBContainer(
            DockerImageName.parse(System.getProperty("test.mongo.image"))
                .asCompatibleSubstituteFor("mongo"));
    mongo.start();
  }

  @AfterAll
  static void stopMongo() {
    if (mongo != null) {
      mongo.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void asyncCommandRetainsCorrelationAndListenerParenting(boolean warmConnection) throws Exception {
    String marker = UUID.randomUUID().toString();
    AtomicReference<String> commandComment = new AtomicReference<>();
    AtomicReference<AgentSpan> listenerWork = new AtomicReference<>();
    AtomicReference<AgentSpan> listenerCaller = new AtomicReference<>();
    AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
    CommandListener observer =
        new CommandListener() {
          @Override
          public void commandStarted(CommandStartedEvent event) {
            if (!"ping".equals(event.getCommandName())) {
              return;
            }
            if (!event.getCommand().containsKey("comment")) {
              return;
            }
            String comment = event.getCommand().getString("comment").getValue();
            if (!comment.startsWith(marker)) {
              return;
            }
            commandComment.set(comment);
            try {
              AgentSpan caller = AgentTracer.activeSpan();
              listenerCaller.set(caller);
              AgentSpan work = tracer.startSpan("test", "command-listener");
              listenerWork.set(work);
              try (ContextScope ignored = tracer.activateSpan(work)) {
                assertSame(work, AgentTracer.activeSpan());
              } finally {
                work.finish();
              }
            } catch (Throwable error) {
              // The driver catches listener exceptions; bring assertion failures back to the test.
              listenerFailure.set(error);
            }
          }

          @Override
          public void commandSucceeded(CommandSucceededEvent event) {}

          @Override
          public void commandFailed(CommandFailedEvent event) {}
        };
    MongoClientSettings settings =
        MongoClientSettings.builder()
            .clusterSettings(
                ClusterSettings.builder()
                    .applyConnectionString(new ConnectionString(mongo.getReplicaSetUrl()))
                    .build())
            .addCommandListener(observer)
            .build();
    try (MongoClient client = MongoClients.create(settings)) {
      MongoDatabase database = client.getDatabase("database");
      if (warmConnection) {
        CompletableFuture<Document> warmed = new CompletableFuture<>();
        database.runCommand(new BsonDocument("ping", new BsonInt32(1)), callback(warmed));
        warmed.get(20, TimeUnit.SECONDS);
        tracer.flush();
        writer.start();
      }
      AgentSpan parent = tracer.startSpan("test", "caller");
      try (ContextScope ignored = tracer.activateSpan(parent)) {
        CompletableFuture<Document> completed = new CompletableFuture<>();
        database.runCommand(
            new BsonDocument("ping", new BsonInt32(1)).append("comment", new BsonString(marker)),
            callback(completed));
        assertNotNull(completed.get(20, TimeUnit.SECONDS));
        assertSame(parent, AgentTracer.activeSpan());
      } finally {
        parent.finish();
      }
      assertNull(listenerFailure.get());
      blockUntilTracesMatch(traces -> traces.stream().mapToInt(List::size).sum() >= 3);
      List<DDSpan> spans = writer.stream().flatMap(List::stream).collect(Collectors.toList());
      List<DDSpan> commands =
          spans.stream()
              .filter(span -> "java-mongo".equals(span.getTag("component")))
              .collect(Collectors.toList());
      assertEquals(
          1, commands.size(), "The pending span must be claimed, not finished and replaced");
      DDSpan command = commands.get(0);
      assertTrue(command.isFinished());
      assertFalse(command.isError());
      assertNotNull(commandComment.get());
      assertTrue(
          commandComment.get().contains("traceparent='" + W3CTraceParent.from(command) + "'"));
      DDSpan work = (DDSpan) listenerWork.get();
      assertNotNull(work);
      assertNotEquals(command.getSpanId(), work.getParentId());
      AgentSpan caller = listenerCaller.get();
      assertEquals(caller == null ? 0 : caller.getSpanId(), work.getParentId());
    }
  }

  private static <T> SingleResultCallback<T> callback(CompletableFuture<T> result) {
    return (value, error) -> {
      if (error != null) {
        result.completeExceptionally(error);
      } else {
        result.complete(value);
      }
    };
  }
}
