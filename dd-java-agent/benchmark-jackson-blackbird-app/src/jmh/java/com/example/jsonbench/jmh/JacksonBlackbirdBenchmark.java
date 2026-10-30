package com.example.jsonbench.jmh;

import com.example.jsonbench.model.Dtos.Order;
import com.example.jsonbench.model.Dtos.SmallDto;
import com.example.jsonbench.model.Samples;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class JacksonBlackbirdBenchmark {

  @Param({"plain", "blackbird"})
  public String mapper;

  @Param({"small", "medium", "list100"})
  public String shape;

  private ObjectWriter writer;
  private ObjectReader reader;
  private Object value;
  private byte[] json;

  static ObjectMapper newMapper(String kind) {
    ObjectMapper m = new ObjectMapper();
    if ("blackbird".equals(kind)) {
      m.registerModule(new BlackbirdModule());
    }
    return m;
  }

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    ObjectMapper m = newMapper(mapper);
    JavaType type;
    switch (shape) {
      case "small":
        value = Samples.small(7);
        type = m.constructType(SmallDto.class);
        break;
      case "medium":
        value = Samples.order(7, 3);
        type = m.constructType(Order.class);
        break;
      case "list100":
        value = Samples.smallList(100);
        type = m.getTypeFactory().constructType(new TypeReference<List<SmallDto>>() {});
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
    writer = m.writerFor(type);
    reader = m.readerFor(type);
    json = writer.writeValueAsBytes(value);
    byte[] plain = newMapper("plain").writerFor(type).writeValueAsBytes(value);
    if (!Arrays.equals(plain, json)) {
      throw new IllegalStateException("plain and " + mapper + " output differ for " + shape);
    }
  }

  @Benchmark
  public byte[] serialize() throws Exception {
    return writer.writeValueAsBytes(value);
  }

  @Benchmark
  public Object deserialize() throws Exception {
    return reader.readValue(json);
  }

  @State(Scope.Benchmark)
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public static class FirstUse {
    @Param({"plain", "blackbird"})
    public String mapper;

    private Order order;

    @Setup(Level.Trial)
    public void setUp() {
      order = Samples.order(7, 3);
    }

    @Benchmark
    public Object newMapperFirstUse() throws Exception {
      ObjectMapper m = newMapper(mapper);
      byte[] bytes = m.writeValueAsBytes(order);
      return m.readValue(bytes, Order.class);
    }
  }
}
