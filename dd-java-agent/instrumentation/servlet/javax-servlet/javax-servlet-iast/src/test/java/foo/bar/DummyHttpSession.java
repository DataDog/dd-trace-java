package foo.bar;

import java.util.Enumeration;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

public class DummyHttpSession implements HttpSession {
  @Override
  public long getCreationTime() {
    return 0;
  }

  @Override
  public String getId() {
    return null;
  }

  @Override
  public long getLastAccessedTime() {
    return 0;
  }

  @Override
  public ServletContext getServletContext() {
    return null;
  }

  @Override
  public void setMaxInactiveInterval(int interval) {}

  @Override
  public int getMaxInactiveInterval() {
    return 0;
  }

  @Override
  public HttpSessionContext getSessionContext() {
    return null;
  }

  @Override
  public Object getAttribute(String name) {
    return null;
  }

  @Override
  public Object getValue(String name) {
    return null;
  }

  @Override
  public Enumeration getAttributeNames() {
    return null;
  }

  @Override
  public String[] getValueNames() {
    return new String[0];
  }

  @Override
  public void setAttribute(String name, Object value) {}

  @Override
  public void putValue(String name, Object value) {}

  @Override
  public void removeAttribute(String name) {}

  @Override
  public void removeValue(String name) {}

  @Override
  public void invalidate() {}

  @Override
  public boolean isNew() {
    return false;
  }
}
