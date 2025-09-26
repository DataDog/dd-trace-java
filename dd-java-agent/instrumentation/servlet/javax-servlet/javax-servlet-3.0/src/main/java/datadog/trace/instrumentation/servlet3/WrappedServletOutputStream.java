package datadog.trace.instrumentation.servlet3;

import datadog.trace.bootstrap.instrumentation.buffer.InjectingPipeOutputStream;
import datadog.trace.util.MethodHandles;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.util.function.LongConsumer;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

public class WrappedServletOutputStream extends ServletOutputStream {
  private final InjectingPipeOutputStream filtered;
  private final ServletOutputStream delegate;

  private static final MethodHandle IS_READY_MH = getMh("isReady");
  private static final MethodHandle SET_WRITELISTENER_MH = getMh("setWriteListener");

  private static MethodHandle getMh(final String name) {
    try {
      return new MethodHandles(ServletOutputStream.class.getClassLoader())
          .method(ServletOutputStream.class, name);
    } catch (Throwable ignored) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
    throw (E) e;
  }

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
      sneakyThrow(e);
      // will never return a value but added for the compiler
      return false;
    }
  }

  public void setWriteListener(WriteListener writeListener) {
    if (SET_WRITELISTENER_MH == null) {
      return;
    }
    try {
      SET_WRITELISTENER_MH.invoke(delegate, writeListener);
    } catch (Throwable e) {
      sneakyThrow(e);
    }
  }

  public void commit() throws IOException {
    filtered.commit();
  }

  public void setFilter(boolean filter) {
    filtered.setFilter(filter);
  }
}
