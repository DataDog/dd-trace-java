package datadog.trace.instrumentation.thrift;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.thrift.InjectAdepter.SETTER;
import static datadog.trace.instrumentation.thrift.ThriftConstants.*;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.*;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapping client output protocol for injecting and propagating the trace header. This is also safe even if the server
 * doesn't deal with it.
 */
public class ClientOutProtocolWrapper extends TProtocolDecorator {

  private static final Logger log = LoggerFactory.getLogger(ClientOutProtocolWrapper.class);

  public ClientOutProtocolWrapper(TProtocol protocol) {
    super(protocol);
  }

  @Override
  public final void writeMessageBegin(final TMessage message) throws TException {
    CLIENT_INJECT_THREAD.set(false);
    super.writeMessageBegin(message);
  }

  @Override
  public final void writeFieldStop() throws TException {
    AgentSpan span = activeSpan();
    boolean injected = CLIENT_INJECT_THREAD.get();
    if (!injected && Optional.ofNullable(span).isPresent()) {
      try {
        Map<String, String> map = new HashMap<>();
        defaultPropagator().inject(span, map, SETTER);
        writeHeader(map);
      } catch (Throwable throwable) {
        if (log.isDebugEnabled()) {
          log.error("inject exception", throwable);
        }
      } finally {
        CLIENT_INJECT_THREAD.set(true);
      }
    }
    super.writeFieldStop();
  }

  private void writeHeader(Map<String, String> header) throws TException {
    super.writeFieldBegin(new TField(DD_MAGIC_FIELD, TType.MAP, DD_MAGIC_FIELD_ID));
    super.writeMapBegin(new TMap(TType.STRING, TType.STRING, header.size()));

    final Set<Map.Entry<String, String>> entries = header.entrySet();
    for (Map.Entry<String, String> entry : entries) {
      super.writeString(entry.getKey());
      super.writeString(entry.getValue());
    }

    super.writeMapEnd();
    super.writeFieldEnd();
  }
}
