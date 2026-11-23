package datadog.trace.instrumentation.mongo;

class BsonBinaryScrubber34Test extends BsonBinaryScrubberTestSupport {
  @Override
  protected BsonScrubber newScrubber() {
    return new BsonScrubber34();
  }
}
