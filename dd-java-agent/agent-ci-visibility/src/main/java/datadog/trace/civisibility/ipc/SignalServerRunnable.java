package datadog.trace.civisibility.ipc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SignalServerRunnable implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignalServerRunnable.class);
  private static final long SELECT_TIMEOUT = TimeUnit.SECONDS.toMillis(5);
  private static final Map<SignalType, Function<ByteBuffer, Signal>> DESERIALIZERS =
      new EnumMap<>(SignalType.class);

  static {
    DESERIALIZERS.put(SignalType.MODULE_EXECUTION_RESULT, ModuleExecutionResult::deserialize);
    DESERIALIZERS.put(
        SignalType.MODULE_COVERAGE_DATA_JACOCO, ModuleCoverageDataJacoco::deserialize);
    DESERIALIZERS.put(SignalType.REPO_INDEX_REQUEST, b -> RepoIndexRequest.INSTANCE);
    DESERIALIZERS.put(SignalType.EXECUTION_SETTINGS_REQUEST, ExecutionSettingsRequest::deserialize);
  }

  private final Selector selector;
  private final int bufferCapacity;
  private final Map<SignalType, Function<Signal, SignalResponse>> signalHandlers;

  SignalServerRunnable(
      Selector selector,
      int bufferCapacity,
      Map<SignalType, Function<Signal, SignalResponse>> signalHandlers) {
    this.selector = selector;
    this.bufferCapacity = bufferCapacity;
    this.signalHandlers = signalHandlers;
  }

  public void run() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        processSelectableKeys();
      } catch (Exception e) {
        LOGGER.error("Error while executing signal server polling loop", e);
      }
    }
    LOGGER.debug("Signal server stopped");
  }

  private void processSelectableKeys() throws IOException {
    selector.select(SELECT_TIMEOUT);

    Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
    while (keyIterator.hasNext()) {
      SelectionKey key = keyIterator.next();
      keyIterator.remove();

      // validity of the key is rechecked multiple times
      // since any of the handlers below might cancel the key
      if (key.isValid() && key.isAcceptable()) {
        accept(key);
      }

      if (key.isValid() && key.isReadable()) {
        read(key);
      }

      if (key.isValid() && key.isWritable()) {
        write(key);
      }
    }
  }

  private void accept(SelectionKey key) throws IOException {
    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
    SocketChannel childChannel = serverChannel.accept();
    childChannel.configureBlocking(false);

    ChannelContext context = new ChannelContext(bufferCapacity, this::onMessage);
    childChannel.register(key.selector(), SelectionKey.OP_READ | SelectionKey.OP_WRITE, context);
  }

  private void read(SelectionKey key) throws IOException {
    ChannelContext context = (ChannelContext) key.attachment();
    SocketChannel childChannel = (SocketChannel) key.channel();
    context.read(childChannel);
  }

  private void write(SelectionKey key) throws IOException {
    ChannelContext context = (ChannelContext) key.attachment();
    SocketChannel childChannel = (SocketChannel) key.channel();
    context.write(childChannel);
  }

  private ByteBuffer[] onMessage(ByteBuffer message) {
    SignalType signalType = SignalType.fromCode(message.get());

    Function<ByteBuffer, Signal> deserializer = DESERIALIZERS.get(signalType);
    if (deserializer == null) {
      LOGGER.error("Deserializer not defined for signal type {}, skipping processing", signalType);
      return serialize(new ErrorResponse("Deserializer not found for " + signalType));
    }

    Signal signal = deserializer.apply(message);
    LOGGER.debug("Received signal: {}", signal);

    Function<Signal, SignalResponse> handler = signalHandlers.get(signalType);
    if (handler == null) {
      LOGGER.warn(
          "No handler registered for signal type {}, skipping signal {}", signalType, signal);
      return serialize(new ErrorResponse("No handler registered for " + signalType));
    }

    SignalResponse response = handler.apply(signal);
    return serialize(response);
  }

  private ByteBuffer[] serialize(SignalResponse response) {
    ByteBuffer payload = response.serialize();
    LOGGER.debug(
        "Serialized response of type {} and size {} bytes",
        response.getType(),
        payload.remaining());

    ByteBuffer header = ByteBuffer.allocate(Integer.BYTES + 1);
    header.putInt(payload.remaining() + 1);
    header.put(response.getType().getCode());
    header.flip();
    return new ByteBuffer[] {header, payload};
  }
}
