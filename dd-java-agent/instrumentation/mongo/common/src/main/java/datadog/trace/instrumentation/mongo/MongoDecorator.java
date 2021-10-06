package datadog.trace.instrumentation.mongo;

import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ConnectionId;
import com.mongodb.connection.ServerId;
import com.mongodb.event.CommandStartedEvent;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonBinaryReader;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class MongoDecorator
    extends DBTypeProcessingDatabaseClientDecorator<CommandStartedEvent> {
  private static final Logger log = LoggerFactory.getLogger(MongoDecorator.class);

  public static final UTF8BytesString MONGO_QUERY = UTF8BytesString.create("mongo.query");

  @Override
  protected final String[] instrumentationNames() {
    return new String[] {"mongo"};
  }

  @Override
  protected final String service() {
    return "mongo";
  }

  @Override
  protected final CharSequence component() {
    return "java-mongo";
  }

  @Override
  protected final CharSequence spanType() {
    return DDSpanTypes.MONGO;
  }

  @Override
  protected final String dbType() {
    return "mongo";
  }

  @Override
  protected final String dbUser(final CommandStartedEvent event) {
    return null;
  }

  @Override
  protected final String dbHostname(CommandStartedEvent event) {
    final ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null) {
      return connectionDescription.getServerAddress().getHost();
    }

    return null;
  }

  public final AgentSpan onStatement(
      @Nonnull final AgentSpan span, @Nonnull final BsonDocument statement) {
    return onStatement(span, statement, null);
  }

  public final AgentSpan onStatement(
      @Nonnull final AgentSpan span,
      @Nonnull final BsonDocument statement,
      @Nullable ContextStore<BsonDocument, ByteBuf> byteBufAccessor) {
    // scrub the Mongo command so that parameters are removed from the string
    span.setResourceName(scrub(statement, byteBufAccessor));
    return span;
  }

  @Override
  protected final String dbInstance(final CommandStartedEvent event) {
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

  protected abstract BsonScrubber newScrubber();

  private String scrub(
      @Nonnull final BsonDocument origin,
      @Nullable ContextStore<BsonDocument, ByteBuf> byteBufAccessor) {
    try (BsonScrubber scrubber = newScrubber()) {
      ByteBuf byteBuf = byteBufAccessor != null ? byteBufAccessor.get(origin) : null;
      if (null == byteBuf) {
        scrubber.pipe(new BsonDocumentReader(origin));
      } else {
        scrubber.pipe(new BsonBinaryReader(byteBuf.duplicate().asNIO()));
      }
      return scrubber.getResourceName();
    }
  }
}
