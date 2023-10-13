package io.vertx.redis.client.impl;

import io.vertx.core.buffer.Buffer;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;

// This is a stub implementation to add the clone method needed by the advice code
public final class RequestImpl implements Request, Cloneable {

  @Override
  public Object clone() throws CloneNotSupportedException {
    return null;
  }

  @Override
  public Request arg(byte[] arg) {
    return null;
  }

  @Override
  public Request arg(Buffer arg) {
    return null;
  }

  @Override
  public Request arg(long arg) {
    return null;
  }

  @Override
  public Request nullArg() {
    return null;
  }

  @Override
  public Command command() {
    return null;
  }
}
