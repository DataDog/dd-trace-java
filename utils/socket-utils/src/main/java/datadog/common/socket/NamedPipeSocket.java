package datadog.common.socket;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

/**
 * This class emulates a socket around a named pipe. Most of the Socket methods are nonsensical in
 * the context of a named pipe and therefore implemented as no-ops
 */
public class NamedPipeSocket extends Socket {
  private final InputStream inputStream;
  private final OutputStream outputStream;
  private final RandomAccessFile randomAccessFile;
  private final FileChannel fileChannel;

  public NamedPipeSocket(File pipe) throws FileNotFoundException {
    randomAccessFile = new RandomAccessFile(pipe, "rw");
    fileChannel = randomAccessFile.getChannel();
    inputStream = Channels.newInputStream(fileChannel);
    outputStream = Channels.newOutputStream(fileChannel);
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (randomAccessFile.getChannel().isOpen()) {
      return inputStream;
    } else {
      throw new IOException("Pipe closed");
    }
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (randomAccessFile.getChannel().isOpen()) {
      return outputStream;
    } else {
      throw new IOException("Pipe closed");
    }
  }

  @Override
  public void close() throws IOException {
    randomAccessFile.close();
  }

  @Override
  public boolean isClosed() {
    return !fileChannel.isOpen();
  }

  @Override
  public boolean isBound() {
    return !isClosed();
  }

  @Override
  public boolean isConnected() {
    return !isClosed();
  }

  @Override
  public boolean isInputShutdown() {
    return isClosed();
  }

  @Override
  public boolean isOutputShutdown() {
    return isClosed();
  }

  @Override
  public void shutdownInput() throws IOException {
    close();
  }

  @Override
  public void shutdownOutput() throws IOException {
    close();
  }

  @Override
  public void bind(SocketAddress local) {
    // do nothing
  }

  @Override
  public void connect(SocketAddress addr) {
    // do nothing
  }

  @Override
  public void connect(SocketAddress addr, int timeout) {
    // do nothing
  }

  @Override
  public SocketChannel getChannel() {
    return null;
  }

  @Override
  public InetAddress getInetAddress() {
    return null;
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return null;
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return null;
  }

  @Override
  public boolean getKeepAlive() {
    return true;
  }

  @Override
  public int getReceiveBufferSize() {
    return 0;
  }

  @Override
  public int getSendBufferSize() {
    return 0;
  }

  @Override
  public int getSoTimeout() {
    return 0;
  }

  @Override
  public void setKeepAlive(boolean on) {
    // do nothing
  }

  @Override
  public void setReceiveBufferSize(int size) {
    // do nothing
  }

  @Override
  public void setSendBufferSize(int size) {
    // do nothing
  }

  @Override
  public void setSoTimeout(int timeout) {
    // do nothing
  }
}
