package datadog.trace.instrumentation.mongo;

import static datadog.trace.api.Config.DBM_PROPAGATION_MODE_FULL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;

import datadog.trace.api.Config;
import datadog.trace.api.W3CTraceParent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;
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

  public static final boolean INJECT_COMMENT = Config.get().isDbmCommentInjectionEnabled();

  /** Main entry point for MongoDB command comment injection */
  public static BsonDocument injectComment(String dbmComment, BsonDocument originalBsonDocument) {
    if (!INJECT_COMMENT) {
      return originalBsonDocument;
    }

    BsonDocument command = originalBsonDocument;
    if (!originalBsonDocument.getClass().equals(BsonDocument.class)) {
      // Create a mutable copy by constructing a new BsonDocument and copying all entries
      // This handles both regular BsonDocument and immutable RawBsonDocument/ByteBufBsonDocument
      command = new BsonDocument();
      command.putAll(originalBsonDocument);
    }

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
          originalBsonDocument.getClass().getSimpleName());
      return originalBsonDocument;
    }
  }

  /** Build comment content using SharedDBCommenter */
  public static String buildComment(AgentSpan dbSpan, String hostname, String dbName) {
    if (!INJECT_COMMENT) {
      return null;
    }

    if (dbSpan.forceSamplingDecision() == null) {
      return null;
    }

    // Set the DBM trace injected tag
    dbSpan.setTag(DBM_TRACE_INJECTED, true);

    // Extract connection details
    String dbService = dbSpan.getServiceName();
    String traceParent =
        Config.get().getDbmPropagationMode().equals(DBM_PROPAGATION_MODE_FULL)
            ? W3CTraceParent.build(
                dbSpan.getTraceId(), dbSpan.getSpanId(), dbSpan.context().getSamplingPriority())
            : null;

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
}
