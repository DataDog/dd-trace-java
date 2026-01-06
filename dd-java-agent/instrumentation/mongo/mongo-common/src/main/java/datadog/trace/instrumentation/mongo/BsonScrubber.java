package datadog.trace.instrumentation.mongo;

import org.bson.BsonReader;

/** A shared interface for the bson scrubbers used in the mongo instrumentations */
public interface BsonScrubber extends AutoCloseable {
  void pipe(BsonReader reader);

  void close();

  String getResourceName();
}
