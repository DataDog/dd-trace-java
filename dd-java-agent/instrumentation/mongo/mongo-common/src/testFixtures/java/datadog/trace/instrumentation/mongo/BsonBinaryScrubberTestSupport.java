package datadog.trace.instrumentation.mongo;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonBinaryReader;
import org.bson.BsonBinaryWriter;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonString;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;
import org.junit.jupiter.api.Test;

public abstract class BsonBinaryScrubberTestSupport {
  protected abstract BsonScrubber newScrubber();

  private static byte[] encode(BsonDocument document) {
    try (BasicOutputBuffer output = new BasicOutputBuffer();
        BsonBinaryWriter writer = new BsonBinaryWriter(output)) {
      new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
      return output.toByteArray();
    }
  }

  private String scrubBinary(BsonDocument document) {
    try (BsonScrubber scrubber = newScrubber();
        BsonBinaryReader reader =
            new BsonBinaryReader(ByteBuffer.wrap(encode(document))) {
              @Override
              protected BsonBinary doReadBinaryData() {
                throw new AssertionError("Redacted binary data must not be materialized");
              }
            }) {
      scrubber.pipe(reader);
      return scrubber.getResourceName();
    }
  }

  private String scrubDocument(BsonDocument document) {
    try (BsonScrubber scrubber = newScrubber();
        BsonDocumentReader reader = new BsonDocumentReader(document)) {
      scrubber.pipe(reader);
      return scrubber.getResourceName();
    }
  }

  @Test
  void skipsBinarySubtypesAndContinuesReadingFields() {
    for (byte subtype : new byte[] {0, 1, 2, 3, 4, 5, (byte) 0x80, (byte) 0xff}) {
      BsonDocument document =
          new BsonDocument("find", new BsonString("items"))
              .append("payload", new BsonBinary(subtype, new byte[16]))
              .append("ordered", BsonBoolean.TRUE);
      assertEquals(
          "{\"find\": \"items\", \"payload\": \"?\", \"ordered\": true}", scrubBinary(document));
    }
  }

  @Test
  void handlesEmptyAndLargeBinaryValues() {
    for (int size : new int[] {0, 16, 4096, 1024 * 1024}) {
      BsonDocument document =
          new BsonDocument("payload", new BsonBinary(new byte[size]))
              .append("find", new BsonString("items"));
      assertEquals("{\"payload\": \"?\", \"find\": \"items\"}", scrubBinary(document));
    }
  }

  @Test
  void preservesArrayAndSubtreeObfuscation() {
    BsonBinary binary = new BsonBinary(new byte[128]);
    BsonDocument document =
        new BsonDocument("find", new BsonString("items"))
            .append("filter", new BsonDocument("values", new BsonArray(asList(binary, binary))))
            .append(
                "writeConcern",
                new BsonDocument("payload", binary).append("ordered", BsonBoolean.TRUE))
            .append("documents", new BsonArray(asList(new BsonDocument("payload", binary))))
            .append("ordered", BsonBoolean.FALSE);
    String expected = scrubDocument(document);
    assertEquals(expected, scrubBinary(document));
    assertEquals(expected, scrubBinary(document));
  }
}
