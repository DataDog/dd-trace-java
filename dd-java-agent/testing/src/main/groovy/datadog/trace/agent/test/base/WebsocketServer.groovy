package datadog.trace.agent.test.base

/**
 * Implement this interface if the tested server supports websockets.
 * The server is supposed to handle both binary and text messages.
 * Each time a message is received, a span called `onRead` should be logged.
 */
interface WebsocketServer extends HttpServer {
  /**
   * Blocks until connected.
   */
  void awaitConnected()
  /**
   * Send text fragments from the current server active session.
   * @param messages
   */
  void serverSendText(String[] messages)
  /**
   * Send binary fragments from the current server active session.
   * @param messages
   */
  void serverSendBinary(byte[][] binaries)
  /**
   * Close the active server session.
   */
  void serverClose()
  /**
   * Set the max size for both text and binary payloads on the active session.
   * @param size
   */
  void setMaxPayloadSize(int size)
  /**
   * If false, receiver tests with multiple chunks will be skipped.
   * @return
   */
  default boolean canSplitLargeWebsocketPayloads() {
    true
  }
}
