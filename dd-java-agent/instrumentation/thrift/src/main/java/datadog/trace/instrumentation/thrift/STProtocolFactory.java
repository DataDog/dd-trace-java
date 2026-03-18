package datadog.trace.instrumentation.thrift;

import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransport;

public class STProtocolFactory implements TProtocolFactory {
  TProtocolFactory inputProtocolFactory;
  public STProtocolFactory(TProtocolFactory inputProtocolFactory){
    this.inputProtocolFactory = inputProtocolFactory;
  }
  @Override
  public TProtocol getProtocol(TTransport tTransport) {
    ServerInProtocolWrapper wrapper = new ServerInProtocolWrapper(inputProtocolFactory.getProtocol(tTransport));
    return wrapper;
  }
}
