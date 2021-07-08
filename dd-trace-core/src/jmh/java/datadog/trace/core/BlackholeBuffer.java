package datadog.trace.core;

import datadog.communication.serialization.StreamingBuffer;
import java.nio.ByteBuffer;
import org.openjdk.jmh.infra.Blackhole;

public class BlackholeBuffer implements StreamingBuffer {

  private final Blackhole blackhole;

  public BlackholeBuffer(Blackhole blackhole) {
    this.blackhole = blackhole;
  }

  @Override
  public int capacity() {
    return 0;
  }

  @Override
  public boolean isDirty() {
    return false;
  }

  @Override
  public void mark() {}

  @Override
  public boolean flush() {
    return true;
  }

  @Override
  public void put(byte b) {
    this.blackhole.consume(b);
  }

  @Override
  public void putShort(short s) {
    this.blackhole.consume(s);
  }

  @Override
  public void putChar(char c) {
    this.blackhole.consume(c);
  }

  @Override
  public void putInt(int i) {
    this.blackhole.consume(i);
  }

  @Override
  public void putLong(long l) {
    this.blackhole.consume(l);
  }

  @Override
  public void putFloat(float f) {
    this.blackhole.consume(f);
  }

  @Override
  public void putDouble(double d) {
    this.blackhole.consume(d);
  }

  @Override
  public void put(byte[] bytes) {
    this.blackhole.consume(bytes);
  }

  @Override
  public void put(byte[] bytes, int offset, int length) {
    this.blackhole.consume(bytes);
  }

  @Override
  public void put(ByteBuffer buffer) {
    this.blackhole.consume(buffer);
  }

  @Override
  public void reset() {}
}
