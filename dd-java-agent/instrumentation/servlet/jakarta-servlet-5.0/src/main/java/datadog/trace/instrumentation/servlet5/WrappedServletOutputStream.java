package datadog.trace.instrumentation.servlet5;

import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.util.function.LongConsumer;

public class WrappedServletOutputStream extends ServletOutputStream {
  private final InjectingPipeOutputStream filtered;
  private final ServletOutputStream delegate;

  public WrappedServletOutputStream(
      ServletOutputStream delegate,
      byte[] marker,
      byte[] contentToInject,
      Runnable onInjected,
      LongConsumer onBytesWritten,
      LongConsumer onInjectionTime) {
    this.filtered =
        new InjectingPipeOutputStream(
            delegate, marker, contentToInject, onInjected, onBytesWritten, onInjectionTime);
    this.delegate = delegate;
  }

  @Override
  public void write(int b) throws IOException {
    filtered.write(b);
  }

  @Override
  public void write(byte[] b) throws IOException {
    filtered.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    filtered.write(b, off, len);
  }

  public void commit() throws IOException {
    filtered.commit();
  }

  @Override
  public void flush() throws IOException {
    filtered.flush();
  }

  @Override
  public void close() throws IOException {
    filtered.close();
  }

  @Override
  public boolean isReady() {
    return delegate.isReady();
  }

  @Override
  public void setWriteListener(WriteListener writeListener) {
    delegate.setWriteListener(writeListener);
  }

  public void setFilter(boolean filter) {
    filtered.setFilter(filter);
  }
}
