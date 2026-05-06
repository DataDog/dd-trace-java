package com.datadog.profiling.otel.benchmark;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

import com.datadog.profiling.otel.proto.ProtobufEncoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for protobuf encoding primitives.
 *
 * <p>Tests the performance of various protobuf encoding operations including varint encoding,
 * fixed-size fields, strings, and nested messages.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 5)
public class ProtobufEncoderBenchmark {

  private ProtobufEncoder encoder;

  // Test data
  private static final String SHORT_STRING = "process";
  private static final String MEDIUM_STRING = "com.example.MyClass.myMethod";
  private static final String LONG_STRING =
      "com.example.very.deep.package.structure.MyVeryLongClassName.myVeryLongMethodNameWithLotsOfParameters";

  private static final byte[] SHORT_BYTES = new byte[] {1, 2, 3, 4, 5};
  private static final byte[] MEDIUM_BYTES = new byte[64];
  private static final byte[] LONG_BYTES = new byte[1024];

  static {
    for (int i = 0; i < MEDIUM_BYTES.length; i++) {
      MEDIUM_BYTES[i] = (byte) i;
    }
    for (int i = 0; i < LONG_BYTES.length; i++) {
      LONG_BYTES[i] = (byte) i;
    }
  }

  @Setup(Level.Invocation)
  public void setup() {
    encoder = new ProtobufEncoder(4096);
  }

  // Varint encoding benchmarks
  @Benchmark
  public void writeVarintSmall(Blackhole bh) {
    encoder.writeVarintField(1, 42); // < 128, single byte
    bh.consume(encoder);
  }

  @Benchmark
  public void writeVarintMedium(Blackhole bh) {
    encoder.writeVarintField(1, 5000); // 2 bytes
    bh.consume(encoder);
  }

  @Benchmark
  public void writeVarintLarge(Blackhole bh) {
    encoder.writeVarintField(1, 1_000_000); // 3+ bytes
    bh.consume(encoder);
  }

  @Benchmark
  public void writeVarintVeryLarge(Blackhole bh) {
    encoder.writeVarintField(1, Long.MAX_VALUE); // max bytes
    bh.consume(encoder);
  }

  // Fixed64 encoding benchmarks
  @Benchmark
  public void writeFixed64(Blackhole bh) {
    encoder.writeFixed64Field(1, 123456789012345L);
    bh.consume(encoder);
  }

  // String encoding benchmarks
  @Benchmark
  public void writeStringShort(Blackhole bh) {
    encoder.writeStringField(1, SHORT_STRING);
    bh.consume(encoder);
  }

  @Benchmark
  public void writeStringMedium(Blackhole bh) {
    encoder.writeStringField(1, MEDIUM_STRING);
    bh.consume(encoder);
  }

  @Benchmark
  public void writeStringLong(Blackhole bh) {
    encoder.writeStringField(1, LONG_STRING);
    bh.consume(encoder);
  }

  // Bytes encoding benchmarks
  @Benchmark
  public void writeBytesShort(Blackhole bh) {
    encoder.writeBytesField(1, SHORT_BYTES);
    bh.consume(encoder);
  }

  @Benchmark
  public void writeBytesMedium(Blackhole bh) {
    encoder.writeBytesField(1, MEDIUM_BYTES);
    bh.consume(encoder);
  }

  @Benchmark
  public void writeBytesLong(Blackhole bh) {
    encoder.writeBytesField(1, LONG_BYTES);
    bh.consume(encoder);
  }

  // Nested message encoding benchmarks
  @Benchmark
  public void writeNestedMessageSimple(Blackhole bh) {
    encoder.writeNestedMessage(
        1,
        enc -> {
          enc.writeVarintField(1, 42);
          enc.writeStringField(2, "test");
        });
    bh.consume(encoder);
  }

  @Benchmark
  public void writeNestedMessageComplex(Blackhole bh) {
    encoder.writeNestedMessage(
        1,
        enc -> {
          enc.writeVarintField(1, 42);
          enc.writeStringField(2, MEDIUM_STRING);
          enc.writeFixed64Field(3, 123456789L);
          enc.writeNestedMessage(
              4,
              enc2 -> {
                enc2.writeVarintField(1, 1);
                enc2.writeVarintField(2, 2);
              });
        });
    bh.consume(encoder);
  }

  // Combined operations (realistic usage)
  @Benchmark
  public void writeTypicalSample(Blackhole bh) {
    encoder.writeVarintField(1, 123); // stack_index
    encoder.writeVarintField(3, 456); // link_index
    encoder.writeVarintField(4, 1); // value
    encoder.writeFixed64Field(5, 1234567890123456L); // timestamp
    bh.consume(encoder);
  }

  @Benchmark
  public void writeTypicalLocation(Blackhole bh) {
    encoder.writeVarintField(1, 0); // mapping_index
    encoder.writeVarintField(2, 0x1234567890ABCDEFL); // address
    encoder.writeNestedMessage(
        3,
        enc -> {
          enc.writeVarintField(1, 100); // function_index
          enc.writeVarintField(2, 42); // line
        });
    bh.consume(encoder);
  }

  @Benchmark
  public void writeTypicalFunction(Blackhole bh) {
    encoder.writeVarintField(1, 10); // name_strindex
    encoder.writeVarintField(2, 10); // system_name_strindex
    encoder.writeVarintField(3, 5); // filename_strindex
    encoder.writeVarintField(4, 100); // start_line
    bh.consume(encoder);
  }

  // Conversion to byte array (measures final serialization overhead)
  @Benchmark
  public void toByteArray(Blackhole bh) {
    encoder.writeVarintField(1, 42);
    encoder.writeStringField(2, MEDIUM_STRING);
    encoder.writeFixed64Field(3, 123456789L);
    byte[] result = encoder.toByteArray();
    bh.consume(result);
  }
}
