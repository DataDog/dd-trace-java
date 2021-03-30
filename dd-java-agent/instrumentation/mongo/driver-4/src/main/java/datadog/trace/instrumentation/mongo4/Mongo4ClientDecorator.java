package datadog.trace.instrumentation.mongo4;

import com.mongodb.connection.ConnectionDescription;
import com.mongodb.event.CommandStartedEvent;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import org.bson.BsonDocument;

public class Mongo4ClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<CommandStartedEvent> {

  public static final UTF8BytesString MONGO_QUERY = UTF8BytesString.create("mongo.query");

  public static final Mongo4ClientDecorator DECORATE = new Mongo4ClientDecorator();

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

    // scrub the Mongo command so that parameters are removed from the string
    final String mongoCmd = scrub(statement);
    span.setResourceName(mongoCmd);
    return onStatement(span, mongoCmd);
  }

  private static String scrub(final BsonDocument origin) {
    try (BsonScrubber scrubber = new BsonScrubber()) {
      scrubber.pipe(origin.asBsonReader());
      return scrubber.toResourceName();
    }
  }
}
