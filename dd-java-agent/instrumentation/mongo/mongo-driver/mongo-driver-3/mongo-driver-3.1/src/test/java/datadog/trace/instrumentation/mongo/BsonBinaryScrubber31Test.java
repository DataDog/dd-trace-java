package datadog.trace.instrumentation.mongo;

class BsonBinaryScrubber31Test extends BsonBinaryScrubberTestSupport {
  @Override
  protected BsonScrubber newScrubber() {
    return new BsonScrubber31();
  }
}
