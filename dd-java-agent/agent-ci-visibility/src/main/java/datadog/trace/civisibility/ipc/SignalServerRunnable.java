package datadog.trace.civisibility.ipc;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SignalServerRunnable implements Runnable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignalServerRunnable.class);

  private static final Map<SignalType, Function<byte[], Signal>> DESERIALIZERS =
      new EnumMap<>(SignalType.class);

  static {
    DESERIALIZERS.put(SignalType.MODULE_EXECUTION_RESULT, ModuleExecutionResult::deserialize);
  }

  private final Selector selector;
  private final int bufferCapacity;
  private final Map<SignalType, Consumer<Signal>> signalHandlers;

  SignalServerRunnable(
      Selector selector, int bufferCapacity, Map<SignalType, Consumer<Signal>> signalHandlers) {
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
    LOGGER.info("Signal server stopped");
  }

  private void processSelectableKeys() throws IOException {
    selector.select();

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

  private void onMessage(byte[] message) {
    SignalType signalType = SignalType.fromCode(message[0]);

    Function<byte[], Signal> deserializer = DESERIALIZERS.get(signalType);
    if (deserializer == null) {
      LOGGER.error("Deserializer not defined for signal type {}, skipping processing", signalType);
      return;
    }

    Signal signal = deserializer.apply(message);
    LOGGER.debug("Received signal: {}", signal);

    Consumer<Signal> handler = signalHandlers.get(signalType);
    if (handler == null) {
      LOGGER.warn("No handler register for signal type {}, skipping signal {}", signalType, signal);
      return;
    }

    handler.accept(signal);
  }
}
