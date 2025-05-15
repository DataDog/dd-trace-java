package datadog.trace.instrumentation.servlet2;

import datadog.trace.api.http.StoredByteBody;
import datadog.trace.instrumentation.servlet.AbstractServletOutputStreamWrapper;
import javax.servlet.ServletOutputStream;

public class ServletOutputStreamWrapper extends AbstractServletOutputStreamWrapper {
  public ServletOutputStreamWrapper(ServletOutputStream os, StoredByteBody storedByteBody) {
    super(os, storedByteBody);
  }
}
