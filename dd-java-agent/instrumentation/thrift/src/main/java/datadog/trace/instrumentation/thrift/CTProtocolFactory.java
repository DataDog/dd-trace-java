package datadog.trace.instrumentation.thrift;

import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransport;

public class CTProtocolFactory implements TProtocolFactory {
  TProtocolFactory inputProtocolFactory;
  public CTProtocolFactory(TProtocolFactory inputProtocolFactory){
    this.inputProtocolFactory = inputProtocolFactory;
  }
  @Override
  public TProtocol getProtocol(TTransport tTransport) {
    ClientOutProtocolWrapper wrapper = new ClientOutProtocolWrapper(inputProtocolFactory.getProtocol(tTransport));
    return wrapper;
  }
}
