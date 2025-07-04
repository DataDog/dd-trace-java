package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream;
import datadog.trace.util.MethodHandles;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

public class WrappedServletOutputStream extends ServletOutputStream {
  private final OutputStream filtered;
  private final ServletOutputStream delegate;

  private static final MethodHandle IS_READY_MH = getMh("isReady");
  private static final MethodHandle SET_WRITELISTENER_MH = getMh("setWriteListener");

  private static final MethodHandle getMh(final String name) {
    try {
      return new MethodHandles(ServletOutputStream.class.getClassLoader())
          .method(ServletOutputStream.class, name);
    } catch (Throwable ignored) {
      return null;
    }
  }

  public WrappedServletOutputStream(
      ServletOutputStream delegate, byte[] marker, byte[] contentToInject, Runnable onInjected) {
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

  public boolean isReady() {
    if (IS_READY_MH == null) {
      return false;
    }
    try {
      return (boolean) IS_READY_MH.invoke(delegate);
    } catch (Throwable e) {
      // how to sneaky throw?
      throw new RuntimeException(e);
    }
  }

  public void setWriteListener(WriteListener writeListener) {
    if (SET_WRITELISTENER_MH == null) {
      return;
    }
    try {
      SET_WRITELISTENER_MH.invoke(delegate, writeListener);
    } catch (Throwable e) {
      // how to sneaky throw?
      throw new RuntimeException(e);
    }
  }
}
