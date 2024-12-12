package datadog.trace.civisibility.ipc;

import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SignalClient implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignalClient.class);

  private static final int BUFFER_CAPACITY = 8192;

  private static final Map<SignalType, Function<ByteBuffer, SignalResponse>> DESERIALIZERS =
      new EnumMap<>(SignalType.class);

  static {
    DESERIALIZERS.put(SignalType.ERROR, ErrorResponse::deserialize);
    DESERIALIZERS.put(SignalType.ACK, b -> AckResponse.INSTANCE);
    DESERIALIZERS.put(SignalType.REPO_INDEX_RESPONSE, RepoIndexResponse::deserialize);
    DESERIALIZERS.put(SignalType.MODULE_SETTINGS_RESPONSE, ExecutionSettingsResponse::deserialize);
  }

  private final SocketChannel socketChannel;
  private final ByteBuffer buffer;

  @SuppressForbidden
  public SignalClient(InetSocketAddress serverAddress, int socketTimeoutMillis) throws IOException {
    if (serverAddress == null) {
      throw new IOException("Cannot open connection to signal server: no address specified");
    }

    socketChannel = SocketChannel.open();
    Socket socket = socketChannel.socket();
    socket.setSoTimeout(socketTimeoutMillis);
    socket.connect(serverAddress, socketTimeoutMillis);

    buffer = ByteBuffer.allocate(BUFFER_CAPACITY);
  }

  @Override
  public void close() throws IOException {
    socketChannel.close();
  }

  public SignalResponse send(Signal signal) throws IOException {
    ByteBuffer message = signal.serialize();
    LOGGER.debug(
        "Sending signal of type {} and size {} bytes", signal.getType(), message.remaining());

    ByteBuffer header = ByteBuffer.allocate(Integer.BYTES + 1);
    header.putInt(message.remaining() + 1); // +1 is for signal type
    header.put(signal.getType().getCode());
    header.flip();
    socketChannel.write(header);
    socketChannel.write(message);
    LOGGER.debug("Signal sent");

    // reading is done this way to make socket channel respect timeout
    Socket socket = socketChannel.socket();
    InputStream inputStream = socket.getInputStream();
    ReadableByteChannel inputChannel = Channels.newChannel(inputStream);

    while (buffer.position() < Integer.BYTES) {
      int read = inputChannel.read(buffer);
      if (read == -1) {
        throw new IOException("Stream closed before response length could be fully read");
      }
    }

    buffer.flip();
    int payloadLength = buffer.getInt();
    ByteBuffer payload = ByteBuffer.allocate(payloadLength);
    payload.put(buffer);
    buffer.flip();

    while (payload.hasRemaining()) {
      int read = inputChannel.read(payload);
      if (read == -1) {
        throw new IOException("Stream closed before response payload could be fully read");
      }
    }

    payload.flip();

    byte signalTypeCode = payload.get();
    SignalType signalType = SignalType.fromCode(signalTypeCode);
    if (signalType == null) {
      throw new IOException("Unknown signal type code " + signalTypeCode);
    }

    Function<ByteBuffer, SignalResponse> deserializer = DESERIALIZERS.get(signalType);
    if (deserializer == null) {
      throw new IOException("Could not find deserializer for signal type " + signalType);
    }

    SignalResponse response = deserializer.apply(payload);
    if (response instanceof ErrorResponse) {
      throw new IOException(getErrorMessage((ErrorResponse) response));
    }
    return response;
  }

  static String getErrorMessage(ErrorResponse response) {
    return "Server returned an error: " + response.getMessage();
  }

  public static final class Factory {
    private final InetSocketAddress signalServerAddress;
    private final Config config;

    public Factory(InetSocketAddress signalServerAddress, Config config) {
      this.signalServerAddress = signalServerAddress;
      this.config = config;
    }

    @Nonnull
    public SignalClient create() {
      if (signalServerAddress == null) {
        throw new IllegalArgumentException(
            "Cannot create signal client: no signal server address configured");
      }
      try {
        return new SignalClient(
            signalServerAddress, config.getCiVisibilitySignalClientTimeoutMillis());
      } catch (IOException e) {
        throw new RuntimeException(
            "Could not instantiate signal client. Address: " + signalServerAddress, e);
      }
    }
  }
}
