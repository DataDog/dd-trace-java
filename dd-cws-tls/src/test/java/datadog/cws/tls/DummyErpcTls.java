package datadog.cws.tls;

import datadog.cws.erpc.Request;

class DummyErpcTls extends CwsErpcTls {
  Request lastRequest;

  public DummyErpcTls(int maxThread) {
    super(maxThread, 5000);
  }

  @Override
  public void sendRequest(Request request) {
    lastRequest = request;
  }
}
