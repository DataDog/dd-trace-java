package datadog.trace.instrumentation.mongo;

public final class MongoDecorator31 extends MongoDecorator {
  public static final MongoDecorator31 INSTANCE = new MongoDecorator31();

  @Override
  protected BsonScrubber newScrubber() {
    return new BsonScrubber31();
  }
}
