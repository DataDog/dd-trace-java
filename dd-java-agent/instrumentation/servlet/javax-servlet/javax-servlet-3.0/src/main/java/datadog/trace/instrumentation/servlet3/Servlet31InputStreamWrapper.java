package datadog.trace.instrumentation.servlet3;

import datadog.trace.api.http.StoredByteBody;
import datadog.trace.instrumentation.servlet.AbstractServletInputStreamWrapper;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

/** Provides additional delegation for servlet 3.1 */
public class Servlet31InputStreamWrapper extends AbstractServletInputStreamWrapper {
  public Servlet31InputStreamWrapper(ServletInputStream is, StoredByteBody storedByteBody) {
    super(is, storedByteBody);
  }

  @Override
  public boolean isFinished() {
    return is.isFinished();
  }

  @Override
  public boolean isReady() {
    return is.isReady();
  }

  @Override
  public void setReadListener(ReadListener readListener) {
    is.setReadListener(readListener);
  }
}
