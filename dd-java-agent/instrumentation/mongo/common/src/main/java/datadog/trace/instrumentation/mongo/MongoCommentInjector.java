package datadog.trace.instrumentation.mongo;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DBM_PROPAGATION_MODE;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.DBM_PROPAGATION_MODE_FULL;
import static datadog.trace.instrumentation.jdbc.JDBCDecorator.INJECT_COMMENT;

import com.mongodb.connection.ConnectionDescription;
import com.mongodb.event.CommandStartedEvent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.jdbc.SharedDBCommenter;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB-specific comment injector for Database Monitoring integration. Handles comment injection
 * and merging for MongoDB BSON commands.
 */
public class MongoCommentInjector {
  private static final Logger log = LoggerFactory.getLogger(MongoCommentInjector.class);

  /** Main entry point for MongoDB command comment injection */
  public static BsonDocument injectComment(String dbmComment, CommandStartedEvent event) {
    if (!INJECT_COMMENT || dbmComment == null || event == null) {
      return event != null ? event.getCommand() : null;
    }

    BsonDocument command = event.getCommand().clone();

    try {
      for (String commentKey : new String[] {"comment", "$comment"}) {
        if (command.containsKey(commentKey)) {
          BsonValue merged = mergeComment(command.get(commentKey), dbmComment);
          command.put(commentKey, merged);
          return command;
        }
      }

      // No existing comment found, default to adding a "comment" field
      command.put("comment", new BsonString(dbmComment));
      return command;
    } catch (Exception e) {
      log.warn(
          "Linking Database Monitoring profiles to spans is not supported for the following query type: {}. "
              + "To disable this feature please set the following environment variable: DD_DBM_PROPAGATION_MODE=disabled",
          event.getCommand().getClass().getSimpleName(),
          e);
      return event.getCommand();
    }
  }

  /** Build comment content using SharedDBCommenter */
  public static String getComment(AgentSpan dbSpan, CommandStartedEvent event) {
    if (!INJECT_COMMENT) {
      return null;
    }

    // Set the DBM trace injected tag
    dbSpan.setTag(DBM_TRACE_INJECTED, true);

    // Extract connection details
    String dbService = dbSpan.getServiceName();
    String hostname = getHostnameFromEvent(event);
    String dbName = event.getDatabaseName();
    String traceParent =
        DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_FULL) ? buildTraceParent(dbSpan) : null;

    // Use shared comment builder directly
    return SharedDBCommenter.buildComment(dbService, "mongodb", hostname, dbName, traceParent);
  }

  /** Merge comment with existing comment values */
  private static BsonValue mergeComment(BsonValue existingComment, String dbmComment) {
    if (existingComment == null) {
      return new BsonString(dbmComment);
    }

    if (existingComment.isString()) {
      String existingStr = existingComment.asString().getValue();
      if (SharedDBCommenter.containsTraceComment(existingStr)) {
        return existingComment; // Already has trace comment, avoid duplication
      }
      // String concatenation with comma separator
      return new BsonString(existingStr + "," + dbmComment);
    } else if (existingComment.isArray()) {
      BsonArray commentArray = existingComment.asArray().clone();
      // Check if any array element already contains trace comment
      for (BsonValue element : commentArray) {
        if (element.isString()
            && SharedDBCommenter.containsTraceComment(element.asString().getValue())) {
          return existingComment; // Already has trace comment, avoid duplication
        }
      }
      // Append to existing array
      commentArray.add(new BsonString(dbmComment));
      return commentArray;
    }

    // Incompatible type, preserve existing comment unchanged
    return existingComment;
  }

  private static String getHostnameFromEvent(CommandStartedEvent event) {
    ConnectionDescription connectionDescription = event.getConnectionDescription();
    if (connectionDescription != null && connectionDescription.getServerAddress() != null) {
      return connectionDescription.getServerAddress().getHost();
    }
    return null;
  }

  private static String buildTraceParent(AgentSpan span) {
    // W3C traceparent format: version-traceId-spanId-flags
    String traceIdHex = span.getTraceId().toHexStringPadded(32);
    String spanIdHex = String.format("%016x", span.getSpanId());
    String flags = "00"; // '01' if sampled, '00' if not
    return String.format("00-%s-%s-%s", traceIdHex, spanIdHex, flags);
  }
}
