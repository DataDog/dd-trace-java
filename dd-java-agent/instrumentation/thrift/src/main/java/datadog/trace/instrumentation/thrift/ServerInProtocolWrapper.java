package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.thrift.ThriftConstants.CONTEXT_THREAD;
import static datadog.trace.instrumentation.thrift.ThriftServerDecorator.SERVER_DECORATOR;


public class ServerInProtocolWrapper extends TProtocolDecorator {
  public static final Logger logger = LoggerFactory.getLogger(ServerInProtocolWrapper.class);

  public ServerInProtocolWrapper(TProtocol protocol) {
    super(protocol);
  }

  public void initial(AbstractContext context) {
    CONTEXT_THREAD.set(context);
  }

  @Override
  public TField readFieldBegin() throws TException {
    final TField field = super.readFieldBegin();
    if (field.id == ThriftConstants.DD_MAGIC_FIELD_ID && field.type == TType.MAP) {
      try {
        TMap tMap = super.readMapBegin();
        Map<String, String> header = new HashMap<>(tMap.size);

        for (int i = 0; i < tMap.size; i++) {
          String key = readString();
          String value = readString();
          header.put(key, value);
        }
        AbstractContext context = CONTEXT_THREAD.get();
        context.setCreatedSpan(true);
        AgentSpan span = SERVER_DECORATOR.createSpan(header, context);
        CONTEXT_THREAD.set(context);
        activateSpan(span);
      } catch (Throwable throwable) {
        logger.error("readFieldBegin exception", throwable);
        throw throwable;
      } finally {
        super.readMapEnd();
        super.readFieldEnd();
//        readFieldEnd();
      }
      return readFieldBegin();
    }

    return field;
  }

//  @Override
//  public void readFieldEnd() {
//    Throwable throwable = null;
//    try {
//      super.readFieldEnd();
//    } catch (TException e) {
//      e.printStackTrace();
//      throwable = new RuntimeException(e);
//    }
//    logger.info("ServerInProtocolWrapper readFieldEnd time:"+System.currentTimeMillis());
//    if (Optional.ofNullable(CONTEXT_THREAD.get()).isPresent() && CONTEXT_THREAD.get().isCreatedSpan()) {
//      AgentScope scope  = activeScope();
//      SERVER_DECORATOR.onError(scope.span(), throwable);
//      SERVER_DECORATOR.beforeFinish(scope.span());
//
//      scope.close();
//      scope.span().finish();
//
//      CONTEXT_THREAD.remove();
//      logger.info("ServerInProtocolWrapper end span time:"+System.currentTimeMillis());
//      logger.info("ServerInProtocolWrapper remove CONTEXT_THREAD");
//    }
//  }

  @Override
  public TMessage readMessageBegin() throws TException {
    final TMessage message = super.readMessageBegin();
    if (Objects.nonNull(message)) {
      AbstractContext context = CONTEXT_THREAD.get();
      if (context == null) {
        context = new Context(null);
        CONTEXT_THREAD.set(context);
      }
      context.setup(message.name);
    }
    return message;
  }

}
