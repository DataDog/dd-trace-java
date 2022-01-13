package datadog.trace.instrumentation.servlet.http;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.api.http.StoredByteBody;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.servlet.ServletInputStream;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

public class ServletInputStreamWrapper extends ServletInputStream {
  public final ServletInputStream is;
  final StoredByteBody storedByteBody;

  private static final MethodHandle NEW_WRAPPER;

  static {
    Class<? extends ServletInputStream> finalClass = ServletInputStreamWrapper.class;
    try {
      Class<?> readListenerClass =
          Class.forName(
              "javax.servlet.ReadListener", true, ServletInputStreamWrapper.class.getClassLoader());
      DynamicType.Unloaded<ServletInputStreamWrapper> wrapperSubCls =
          new ByteBuddy()
              .subclass(
                  ServletInputStreamWrapper.class, ConstructorStrategy.Default.IMITATE_SUPER_CLASS)
              .method(named("isFinished").and(ElementMatchers.takesNoArguments()))
              .intercept(MethodDelegation.toField("is"))
              .method(named("isReady").and(ElementMatchers.takesNoArguments()))
              .intercept(MethodDelegation.toField("is"))
              .method(named("setReadListener").and(takesArguments(readListenerClass)))
              .intercept(MethodDelegation.toField("is"))
              .make();
      finalClass = wrapperSubCls.load(ServletInputStreamWrapper.class.getClassLoader()).getLoaded();
    } catch (Exception e) {
    }

    MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
    MethodType mt =
        MethodType.methodType(void.class, ServletInputStream.class, StoredByteBody.class);
    try {
      NEW_WRAPPER = publicLookup.findConstructor(finalClass, mt);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public ServletInputStreamWrapper(ServletInputStream is, StoredByteBody storedByteBody) {
    this.is = is;
    this.storedByteBody = storedByteBody;
  }

  public static ServletInputStreamWrapper create(
      ServletInputStream is, StoredByteBody storedByteBody) {
    try {
      return (ServletInputStreamWrapper) NEW_WRAPPER.invoke(is, storedByteBody);
    } catch (Throwable throwable) {
      throw new RuntimeException(throwable);
    }
  }

  @Override
  public int read(byte[] b) throws IOException {
    int numRead = is.read(b);
    if (numRead > 0) {
      storedByteBody.appendData(b, 0, numRead);
    } else if (numRead == -1) {
      storedByteBody.maybeNotify();
    }
    return numRead;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int numRead = is.read(b, off, len);
    if (numRead > 0) {
      storedByteBody.appendData(b, off, off + numRead);
    } else if (numRead == -1) {
      storedByteBody.maybeNotify();
    }
    return numRead;
  }

  @Override
  public int read() throws IOException {
    int read = is.read();
    if (read == -1) {
      storedByteBody.maybeNotify();
    }
    storedByteBody.appendData(read);
    return read;
  }

  @Override
  public int readLine(byte[] b, int off, int len) throws IOException {
    int numRead = is.readLine(b, off, len);
    if (numRead > 0) {
      storedByteBody.appendData(b, off, off + numRead);
    } else if (numRead == -1) {
      storedByteBody.maybeNotify();
    }

    return numRead;
  }

  @Override
  public long skip(long n) throws IOException {
    return is.skip(n);
  }

  @Override
  public int available() throws IOException {
    return is.available();
  }

  @Override
  public void close() throws IOException {
    is.close();
    storedByteBody.maybeNotify();
  }

  @Override
  public void mark(int readlimit) {
    is.mark(readlimit);
  }

  @Override
  public void reset() throws IOException {
    is.reset();
  }

  @Override
  public boolean markSupported() {
    return is.markSupported();
  }
}
