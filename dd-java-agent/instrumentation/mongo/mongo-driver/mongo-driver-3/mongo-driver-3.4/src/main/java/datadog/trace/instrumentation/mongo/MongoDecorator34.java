package datadog.trace.instrumentation.mongo;

public final class MongoDecorator34 extends MongoDecorator {
  public static final MongoDecorator34 INSTANCE = new MongoDecorator34();

  @Override
  protected BsonScrubber newScrubber() {
    return new BsonScrubber34();
  }
}
