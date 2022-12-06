package com.datadog.debugger;

import java.io.IOException;
/*
This is the minimal case that reproduce a LinkageError: attempted duplicate class definition
 */
public class CapturedSnapshot12 {
  private boolean executed;

  public Object execute() throws IOException {
    synchronized (this) {}
    try {
      Object result = new Object();
      if (result == null) throw new IOException("Canceled");
      return result;
    } catch (IOException e) {
      throw e;
    }
  }
}
