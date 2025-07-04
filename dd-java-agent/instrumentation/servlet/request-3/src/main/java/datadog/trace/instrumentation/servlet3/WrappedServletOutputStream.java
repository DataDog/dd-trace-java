package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

public class WrappedServletOutputStream extends ServletOutputStream {
  private final OutputStream filtered;
  private final ServletOutputStream delegate;

  public WrappedServletOutputStream(
      ServletOutputStream delegate,
      byte[] marker,
      byte[] contentToInject,
      Consumer<Void> onInjected) {
    this.filtered = new InjectingPipeOutputStream(delegate, marker, contentToInject, onInjected);
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
}
