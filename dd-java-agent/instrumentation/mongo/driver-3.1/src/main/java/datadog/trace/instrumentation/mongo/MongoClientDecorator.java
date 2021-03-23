package datadog.trace.instrumentation.mongo;

import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ConnectionId;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandStartedEvent;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;

public class MongoClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<CommandStartedEvent> {

  public static final UTF8BytesString MONGO_QUERY = UTF8BytesString.create("mongo.query");

  public static final MongoClientDecorator DECORATE = new MongoClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"mongo"};
  }

  @Override
  protected String service() {
    return "mongo";
  }

  @Override
  protected CharSequence component() {
    return "java-mongo";
  }

  @Override
  protected CharSequence spanType() {
    return DDSpanTypes.MONGO;
  }

  @Override
  protected String dbType() {
    return "mongo";
  }

  @Override
  protected String dbUser(final CommandStartedEvent event) {
    return null;
  }

  @Override
  protected String dbInstance(final CommandStartedEvent event) {
    // Use description if set.
    final ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null) {
      final ConnectionId connectionId = connectionDescription.getConnectionId();
      if (connectionId != null) {
        final ServerId serverId = connectionId.getServerId();
        if (serverId != null) {
          final ClusterId clusterId = serverId.getClusterId();
          if (clusterId != null) {
            final String description = clusterId.getDescription();
            if (description != null) {
              return description;
            }
          }
        }
      }
    }
    // Fallback to db name.
    return event.getDatabaseName();
  }

  @Override
  protected String dbHostname(CommandStartedEvent event) {
    final ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null) {
      return connectionDescription.getServerAddress().getHost();
    }

    return null;
  }

  public AgentSpan onStatement(final AgentSpan span, final BsonDocument statement) {
    span.setResourceName(scrub(statement));
    return span;
  }

  private static String scrub(final BsonDocument origin) {
    try (BsonScrubber scrubber = new BsonScrubber()) {
      scrubber.pipe(new BsonDocumentReader(origin));
      return scrubber.toResourceName();
    }
  }
}
