package datadog.trace.instrumentation.servlet.http.common;

import datadog.trace.api.http.StoredBodyListener;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.api.http.StoredCharBody;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BodyCapturingHttpServletRequest extends HttpServletRequestWrapper
    implements StoredBodySupplier {

  private static final Logger log = LoggerFactory.getLogger(BodyCapturingHttpServletRequest.class);

  private final AtomicReference<StoredBodySupplier> storedBodyRef = new AtomicReference<>();
  private final StoredBodyListener listener;

  /**
   * Constructs a request object wrapping the given request.
   *
   * @param request
   * @throws IllegalArgumentException if the request is null
   */
  public BodyCapturingHttpServletRequest(
      HttpServletRequest request, StoredBodyListener bodyListener) {
    super(request);
    listener = bodyListener;
  }

  @Override
  public String get() {
    StoredBodySupplier storedBody = storedBodyRef.get();
    if (storedBody == null) {
      return null;
    }

    return storedBody.get();
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    ServletInputStream is = super.getInputStream();
    String encoding = getCharacterEncoding();
    ServletInputStreamWrapper servletInputStreamWrapper = new ServletInputStreamWrapper(is);
    try {
      if (encoding != null) {
        Charset charset = Charset.forName(encoding);
        servletInputStreamWrapper.setCharset(charset);
      } else {
        log.debug("Encoding is not known by the Servlet API");
      }
    } catch (IllegalArgumentException iae) {
      log.info("Encoding {} not recognized", encoding);
    }

    return servletInputStreamWrapper;
  }

  private StoredByteBody getStoredByteBody() {
    StoredBodySupplier storedBody = this.storedBodyRef.get();
    if (!(storedBody instanceof StoredByteBody)) {
      if (storedBody != null) {
        log.info(
            "Replacing {} with new StoredByteBody. " + "Call to getInputStream() and getReader()?",
            storedBody);
      }
      StoredByteBody newStoredByteBody = new StoredByteBody(listener);
      if (!this.storedBodyRef.compareAndSet(storedBody, newStoredByteBody)) {
        return getStoredByteBody();
      }
      return newStoredByteBody;
    } else {
      return (StoredByteBody) storedBody;
    }
  }

  private StoredCharBody getStoredCharBody() {
    StoredBodySupplier storedBody = this.storedBodyRef.get();
    if (!(storedBody instanceof StoredCharBody)) {
      if (storedBody != null) {
        log.info(
            "Replacing {} with new StoredCharBody. " + "Call to getInputStream() and getReader()?",
            storedBody);
      }
      StoredCharBody newStoredCharBody = new StoredCharBody(listener);
      if (!this.storedBodyRef.compareAndSet(storedBody, newStoredCharBody)) {
        return getStoredCharBody();
      }
      return newStoredCharBody;
    } else {
      return (StoredCharBody) storedBody;
    }
  }

  @Override
  public BufferedReader getReader() throws IOException {
    BufferedReader reader = super.getReader();
    return new BufferedReaderWrapper(reader);
  }

  class BufferedReaderWrapper extends BufferedReader {
    private final BufferedReader reader;

    public BufferedReaderWrapper(BufferedReader reader) {
      super(reader);
      this.reader = reader;
    }

    @Override
    public int read() throws IOException {
      int read = this.reader.read();
      if (read >= 0) {
        getStoredCharBody().appendData(read);
      } else {
        getStoredCharBody().maybeNotify();
      }
      return read;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      int read = this.reader.read(cbuf, off, len);
      if (read > 0) {
        getStoredCharBody().appendData(cbuf, off, off + read);
      } else {
        getStoredCharBody().maybeNotify();
      }
      return read;
    }

    @Override
    public String readLine() throws IOException {
      String read = this.reader.readLine();
      if (read == null) {
        getStoredCharBody().maybeNotify();
        return null;
      }
      StoredCharBody storedCharBody = getStoredCharBody();
      storedCharBody.appendData(read);
      storedCharBody.appendData('\n');
      return read;
    }

    @Override
    public long skip(long n) throws IOException {
      return this.reader.skip(n);
    }

    @Override
    public boolean ready() throws IOException {
      return this.reader.ready();
    }

    @Override
    public boolean markSupported() {
      return this.reader.markSupported();
    }

    @Override
    public void mark(int readAheadLimit) throws IOException {
      this.reader.mark(readAheadLimit);
    }

    @Override
    public void reset() throws IOException {
      this.reader.reset();
    }

    @Override
    public void close() throws IOException {
      this.reader.close();
      getStoredCharBody().maybeNotify();
    }

    @Override
    public int read(CharBuffer target) throws IOException {
      int initPos = target.position();
      int read = this.reader.read(target);
      if (read > 0) {
        StoredCharBody storedCharBody = getStoredCharBody();
        for (int i = initPos; i < initPos + read; i++) {
          storedCharBody.appendData(target.get(i));
        }
      } else {
        getStoredCharBody().maybeNotify();
      }
      return read;
    }

    @Override
    public int read(char[] cbuf) throws IOException {
      int read = this.reader.read(cbuf);
      if (read > 0) {
        getStoredCharBody().appendData(cbuf, 0, read);
      } else {
        getStoredCharBody().maybeNotify();
      }
      return read;
    }
  }

  class ServletInputStreamWrapper extends ServletInputStream {
    final ServletInputStream is;

    ServletInputStreamWrapper(ServletInputStream is) {
      this.is = is;
    }

    void setCharset(Charset charset) {
      getStoredByteBody().setCharset(charset);
    }

    @Override
    public int read(byte[] b) throws IOException {
      int numRead = is.read(b);
      if (numRead > 0) {
        getStoredByteBody().appendData(b, 0, numRead);
      } else {
        getStoredByteBody().maybeNotify();
      }
      return numRead;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int numRead = is.read(b, off, len);
      if (numRead > 0) {
        getStoredByteBody().appendData(b, off, off + numRead);
      } else {
        getStoredByteBody().maybeNotify();
      }
      return numRead;
    }

    @Override
    public int read() throws IOException {
      int read = is.read();
      if (read == -1) {
        getStoredByteBody().maybeNotify();
      }
      getStoredByteBody().appendData(read);
      return read;
    }

    @Override
    public int readLine(byte[] b, int off, int len) throws IOException {
      int numRead = is.readLine(b, off, len);
      if (numRead > 0) {
        getStoredByteBody().appendData(b, off, off + numRead);
      }
      // else don't notify
      // as if it can't find a full line, it fails

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
      getStoredByteBody().maybeNotify();
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
}
