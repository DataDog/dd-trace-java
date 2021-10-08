package datadog.trace.instrumentation.mongo;

import java.util.Map;
import org.bson.BsonArray;
import org.bson.BsonBinary;
import org.bson.BsonDbPointer;
import org.bson.BsonDocument;
import org.bson.BsonJavaScriptWithScope;
import org.bson.BsonReader;
import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.BsonType;
import org.bson.BsonValue;
import org.bson.BsonWriter;
import org.bson.types.ObjectId;

public final class BsonScrubber31 implements BsonWriter, BsonScrubber {

  private static final ThreadLocal<Context> CONTEXT =
      new ThreadLocal<Context>() {
        @Override
        protected Context initialValue() {
          return new Context();
        }
      };

  private final Context context;
  private boolean obfuscate = true;

  public BsonScrubber31() {
    this.context = CONTEXT.get();
  }

  @Override
  public void close() {
    context.clear();
  }

  public String getResourceName() {
    return context.asString();
  }

  private void applyObfuscationPolicy(String name) {
    if (null != name && !context.ignoreSubTree()) {
      switch (name) {
        case "documents":
        case "deletes":
        case "updates": // we don't want to record data in resource names!
        case "$in":
        case "$setOnInsert":
        case "$set":
        case "arrayFilters": // collapse long lists
          context.discardSubTree();
          obfuscate = true;
          break;
        case "writeConcern":
        case "readConcern":
          context.keepSubTree();
          obfuscate = false;
          break;
        case "$explain":
        case "$db":
        case "ordered":
        case "delete":
        case "insert":
        case "upsert":
        case "update":
        case "find":
        case "count":
        case "create":
          obfuscate = false;
          break;
        default:
          obfuscate = !context.disableObfuscation();
      }
    }
  }

  @Override
  public void flush() {}

  private void writeObfuscated() {
    context.write("\"?\"");
  }

  @Override
  public void writeBinaryData(BsonBinary binary) {
    writeObfuscated();
  }

  @Override
  public void writeBinaryData(String name, BsonBinary binary) {
    writeName(name);
    writeBinaryData(binary);
  }

  @Override
  public void writeBoolean(boolean value) {
    if (obfuscate) {
      writeObfuscated();
    } else {
      context.write(value);
    }
  }

  @Override
  public void writeBoolean(String name, boolean value) {
    writeName(name);
    writeBoolean(value);
  }

  @Override
  public void writeDateTime(long value) {
    writeObfuscated();
  }

  @Override
  public void writeDateTime(String name, long value) {
    writeName(name);
    writeDateTime(value);
  }

  @Override
  public void writeDBPointer(BsonDbPointer value) {
    writeObfuscated();
  }

  @Override
  public void writeDBPointer(String name, BsonDbPointer value) {
    writeName(name);
    writeDBPointer(value);
  }

  @Override
  public void writeDouble(double value) {
    writeObfuscated();
  }

  @Override
  public void writeDouble(String name, double value) {
    writeName(name);
    writeDouble(value);
  }

  @Override
  public void writeEndArray() {
    context.write(']');
  }

  @Override
  public void writeEndDocument() {
    context.write('}');
    context.endDocument();
  }

  @Override
  public void writeInt32(int value) {
    if (obfuscate) {
      writeObfuscated();
    } else {
      context.write(value);
    }
  }

  @Override
  public void writeInt32(String name, int value) {
    writeName(name);
    writeInt32(value);
  }

  @Override
  public void writeInt64(long value) {
    writeObfuscated();
  }

  @Override
  public void writeInt64(String name, long value) {
    writeName(name);
    writeInt64(value);
  }

  @Override
  public void writeJavaScript(String code) {
    writeObfuscated();
  }

  @Override
  public void writeJavaScript(String name, String code) {
    writeName(name);
    writeJavaScript(code);
  }

  @Override
  public void writeJavaScriptWithScope(String code) {
    writeObfuscated();
  }

  @Override
  public void writeJavaScriptWithScope(String name, String code) {
    writeName(name);
    writeJavaScriptWithScope(code);
  }

  @Override
  public void writeMaxKey() {
    writeObfuscated();
  }

  @Override
  public void writeMaxKey(String name) {
    writeName(name);
    writeMaxKey();
  }

  @Override
  public void writeMinKey() {
    writeObfuscated();
  }

  @Override
  public void writeMinKey(String name) {
    writeName(name);
    writeMinKey();
  }

  @Override
  public void writeName(String name) {
    applyObfuscationPolicy(name);
    if (name != null) {
      context.write('\"');
      context.write(name);
      context.write('\"');
      context.write(':');
      context.write(' ');
    }
  }

  @Override
  public void writeNull() {
    writeObfuscated();
  }

  @Override
  public void writeNull(String name) {
    writeName(name);
    writeNull();
  }

  @Override
  public void writeObjectId(ObjectId objectId) {
    writeObfuscated();
  }

  @Override
  public void writeObjectId(String name, ObjectId objectId) {
    writeName(name);
    writeObjectId(objectId);
  }

  @Override
  public void writeRegularExpression(BsonRegularExpression regularExpression) {
    writeString(regularExpression.getPattern());
  }

  @Override
  public void writeRegularExpression(String name, BsonRegularExpression regularExpression) {
    writeName(name);
    writeRegularExpression(regularExpression);
  }

  @Override
  public void writeStartArray() {
    context.write('[');
  }

  @Override
  public void writeStartArray(String name) {
    writeName(name);
    writeStartArray();
  }

  @Override
  public void writeStartDocument() {
    context.startDocument();
    context.write('{');
  }

  @Override
  public void writeStartDocument(String name) {
    writeName(name);
    writeStartDocument();
  }

  @Override
  public void writeString(String value) {
    if (value.startsWith("$")) {
      context.write(value);
    } else if (obfuscate) {
      writeObfuscated();
    } else {
      context.write('\"');
      context.write(value);
      context.write('\"');
    }
  }

  @Override
  public void writeString(String name, String value) {
    writeName(name);
    writeString(value);
  }

  @Override
  public void writeSymbol(String value) {
    writeString(value);
  }

  @Override
  public void writeSymbol(String name, String value) {
    writeName(name);
    writeSymbol(value);
  }

  @Override
  public void writeTimestamp(BsonTimestamp value) {
    writeObfuscated();
  }

  @Override
  public void writeTimestamp(String name, BsonTimestamp value) {
    writeName(name);
    writeTimestamp(value);
  }

  @Override
  public void writeUndefined() {
    writeObfuscated();
  }

  @Override
  public void writeUndefined(String name) {
    writeName(name);
    writeUndefined();
  }

  @Override
  public void pipe(BsonReader reader) {
    pipeDocument(null, reader);
  }

  private void pipeDocument(String attribute, final BsonReader reader) {
    reader.readStartDocument();
    if (null == attribute) {
      writeStartDocument();
    } else {
      writeStartDocument(attribute);
    }
    BsonType type = reader.readBsonType();
    while (type != BsonType.END_OF_DOCUMENT) {
      pipeValue(reader.readName(), reader);
      type = reader.readBsonType();
      nextValue(type);
    }
    reader.readEndDocument();
    writeEndDocument();
  }

  private void pipeJavascriptWithScope(String attribute, BsonReader reader) {
    writeJavaScriptWithScope(attribute, reader.readJavaScriptWithScope());
    pipeDocument(attribute, reader);
  }

  private void pipeValue(String attribute, BsonReader reader) {
    switch (reader.getCurrentBsonType()) {
      case DOCUMENT:
        pipeDocument(attribute, reader);
        break;
      case ARRAY:
        pipeArray(attribute, reader);
        break;
      case DOUBLE:
        writeDouble(attribute, reader.readDouble());
        break;
      case STRING:
        writeString(attribute, reader.readString());
        break;
      case BINARY:
        writeBinaryData(attribute, reader.readBinaryData());
        break;
      case UNDEFINED:
        reader.readUndefined();
        writeUndefined();
        break;
      case OBJECT_ID:
        writeObjectId(attribute, reader.readObjectId());
        break;
      case BOOLEAN:
        writeBoolean(attribute, reader.readBoolean());
        break;
      case DATE_TIME:
        writeDateTime(attribute, reader.readDateTime());
        break;
      case NULL:
        reader.readNull();
        writeNull(attribute);
        break;
      case REGULAR_EXPRESSION:
        writeRegularExpression(attribute, reader.readRegularExpression());
        break;
      case JAVASCRIPT:
        writeJavaScript(attribute, reader.readJavaScript());
        break;
      case SYMBOL:
        writeSymbol(attribute, reader.readSymbol());
        break;
      case JAVASCRIPT_WITH_SCOPE:
        pipeJavascriptWithScope(attribute, reader);
        break;
      case INT32:
        writeInt32(attribute, reader.readInt32());
        break;
      case TIMESTAMP:
        writeTimestamp(attribute, reader.readTimestamp());
        break;
      case INT64:
        writeInt64(attribute, reader.readInt64());
        break;
      case MIN_KEY:
        reader.readMinKey();
        writeMinKey(attribute);
        break;
      case DB_POINTER:
        writeDBPointer(attribute, reader.readDBPointer());
        break;
      case MAX_KEY:
        reader.readMaxKey();
        writeMaxKey(attribute);
        break;
      default:
        reader.skipValue();
        writeUndefined(attribute);
    }
  }

  private void pipeDocument(String attribute, BsonDocument value) {
    writeStartDocument(attribute);
    for (Map.Entry<String, BsonValue> cur : value.entrySet()) {
      pipeValue(cur.getKey(), cur.getValue());
    }
    writeEndDocument();
  }

  private void pipeArray(String attribute, BsonReader reader) {
    reader.readStartArray();
    writeStartArray(attribute);
    BsonType type = reader.readBsonType();
    while (type != BsonType.END_OF_DOCUMENT) {
      pipeValue(null, reader);
      type = reader.readBsonType();
      nextValue(type);
    }
    reader.readEndArray();
    writeEndArray();
  }

  private void pipeArray(String attribute, final BsonArray array) {
    writeStartArray(attribute);
    for (BsonValue cur : array) {
      pipeValue(null, cur);
    }
    writeEndArray();
  }

  private void pipeJavascriptWithScope(
      String attribute, final BsonJavaScriptWithScope javaScriptWithScope) {
    writeJavaScriptWithScope(javaScriptWithScope.getCode());
    pipeDocument(attribute, javaScriptWithScope.getScope());
  }

  private void pipeValue(String attribute, final BsonValue value) {
    switch (value.getBsonType()) {
      case DOCUMENT:
        pipeDocument(attribute, value.asDocument());
        break;
      case ARRAY:
        pipeArray(attribute, value.asArray());
        break;
      case DOUBLE:
        writeDouble(attribute, value.asDouble().getValue());
        break;
      case STRING:
        writeString(attribute, value.asString().getValue());
        break;
      case BINARY:
        writeBinaryData(attribute, value.asBinary());
        break;
      case UNDEFINED:
        writeUndefined();
        break;
      case OBJECT_ID:
        writeObjectId(attribute, value.asObjectId().getValue());
        break;
      case BOOLEAN:
        writeBoolean(attribute, value.asBoolean().getValue());
        break;
      case DATE_TIME:
        writeDateTime(attribute, value.asDateTime().getValue());
        break;
      case NULL:
        writeNull();
        break;
      case REGULAR_EXPRESSION:
        writeRegularExpression(attribute, value.asRegularExpression());
        break;
      case JAVASCRIPT:
        writeJavaScript(attribute, value.asJavaScript().getCode());
        break;
      case SYMBOL:
        writeSymbol(attribute, value.asSymbol().getSymbol());
        break;
      case JAVASCRIPT_WITH_SCOPE:
        pipeJavascriptWithScope(attribute, value.asJavaScriptWithScope());
        break;
      case INT32:
        writeInt32(attribute, value.asInt32().getValue());
        break;
      case TIMESTAMP:
        writeTimestamp(attribute, value.asTimestamp());
        break;
      case INT64:
        writeInt64(attribute, value.asInt64().getValue());
        break;
      case MIN_KEY:
        writeMinKey(attribute);
        break;
      case DB_POINTER:
        writeDBPointer(attribute, value.asDBPointer());
        break;
      case MAX_KEY:
        writeMaxKey(attribute);
        break;
      default:
        writeUndefined(attribute);
    }
  }

  private void nextValue(BsonType type) {
    if (type != BsonType.END_OF_DOCUMENT) {
      context.write(',');
      context.write(' ');
    }
  }
}
