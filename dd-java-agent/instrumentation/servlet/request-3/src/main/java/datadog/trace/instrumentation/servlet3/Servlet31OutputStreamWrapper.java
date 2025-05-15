package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.http.StoredByteBody;
import datadog.trace.instrumentation.servlet.AbstractServletOutputStreamWrapper;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

/** Provides additional delegation for servlet 3.1 output streams */
public class Servlet31OutputStreamWrapper extends AbstractServletOutputStreamWrapper {
  public Servlet31OutputStreamWrapper(ServletOutputStream os, StoredByteBody storedByteBody) {
    super(os, storedByteBody);
  }

  @Override
  public boolean isReady() {
    return os.isReady();
  }

  @Override
  public void setWriteListener(WriteListener writeListener) {
    os.setWriteListener(writeListener);
  }
}
