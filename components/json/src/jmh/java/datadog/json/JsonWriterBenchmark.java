package datadog.json;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressWarnings("unused")
public class JsonWriterBenchmark {
  @Benchmark
  public void writeSimpleArray(Blackhole blackhole) {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginArray()
          .beginObject()
          .name("true")
          .value(true)
          .endObject()
          .beginObject()
          .name("false")
          .value(false)
          .endObject()
          .endArray();
      blackhole.consume(writer.toString());
    }
  }

  @Benchmark
  public void writeComplexArray(Blackhole blackhole) {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginArray()
          .value("first level")
          .beginArray()
          .value("second level")
          .beginArray()
          .value("third level")
          .beginObject()
          .name("key")
          .value("value")
          .endObject()
          .beginObject()
          .name("key")
          .value("value")
          .endObject()
          .endArray() // second level
          .beginObject()
          .name("key")
          .value("value")
          .endObject()
          .endArray() // first level
          .beginObject()
          .name("key")
          .value("value")
          .endObject()
          .value("last value")
          .endArray();
      blackhole.consume(writer.toString());
    }
  }

  @Benchmark
  public void writeComplexObject(Blackhole blackhole) {
    try (JsonWriter writer = new JsonWriter()) {
      writer
          .beginObject()
          .name("attrs")
          .beginObject()
          .name("attr1")
          .value("value1")
          .name("attr2")
          .value("value2")
          .endObject()
          .name("data")
          .beginArray()
          .beginObject()
          .name("x")
          .value(1)
          .name("y")
          .value(12.3)
          .endObject()
          .beginObject()
          .name("x")
          .value(2)
          .name("y")
          .value(4.56)
          .endObject()
          .beginObject()
          .name("x")
          .value(3)
          .name("y")
          .value(789)
          .endObject()
          .endArray()
          .endObject();
      blackhole.consume(writer.toString());
    }
  }
}
