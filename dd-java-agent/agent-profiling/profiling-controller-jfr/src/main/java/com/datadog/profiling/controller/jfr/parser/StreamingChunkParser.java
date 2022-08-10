package com.datadog.profiling.controller.jfr.parser;

import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streaming, almost zero-allocation, JFR chunk parser implementation. <br>
 * This is an MVP of a chunk parser allowing to stream the JFR events efficiently. The parser
 * notifies its listeners as the data becomes available. Because of this it is possible for the
 * metadata events to come 'out-of-band' (although not very probable) and it is up to the caller to
 * deal with that eventuality. <br>
 */
public final class StreamingChunkParser {
  private static final Logger log = LoggerFactory.getLogger(StreamingChunkParser.class);

  /**
   * Parse the given JFR recording stream.<br>
   * The parser will process the recording stream and call the provided listener in this order:
   *
   * <ol>
   *   <li>listener.onRecordingStart()
   *   <li>listener.onChunkStart()
   *   <li>listener.onEvent() | listener.onMetadata()
   *   <li>listener.onChunkEnd()
   *   <li>listener.onRecordingEnd()
   * </ol>
   *
   * @param inputStream the JFR recording stream it will be closed when the parsing is over
   * @param listener the parser listener
   * @throws IOException
   */
  public void parse(InputStream inputStream, ChunkParserListener listener) throws IOException {
    try (RecordingStream stream = new RecordingStream(inputStream)) {
      parse(stream, listener);
    }
  }

  private void parse(RecordingStream stream, ChunkParserListener listener) throws IOException {
    if (stream.available() == 0) {
      return;
    }
    try {
      listener.onRecordingStart();
      int chunkCounter = 1;
      while (stream.available() > 0) {
        long chunkStartPos = stream.position();
        ChunkHeader header = new ChunkHeader(stream);
        if (!listener.onChunkStart(chunkCounter, header)) {
          log.debug(
              "'onChunkStart' returned false. Skipping metadata and events for chunk {}",
              chunkCounter);
          stream.skip(header.size - (stream.position() - chunkStartPos));
          listener.onChunkEnd(chunkCounter, true);
          continue;
        }
        long chunkEndPos = chunkStartPos + (int) header.size;
        while (stream.position() < chunkEndPos) {
          long eventStartPos = stream.position();
          stream.mark(20); // max 2 varints ahead
          int eventSize = (int) stream.readVarint();
          if (eventSize > 0) {
            long eventType = stream.readVarint();
            if (eventType == 0) {
              // metadata
              stream.reset(); // roll-back the stream to the event start
              MetadataEvent m = new MetadataEvent(stream);
              if (!listener.onMetadata(m)) {
                log.debug(
                    "'onMetadata' returned false. Skipping events for chunk {}", chunkCounter);
                stream.skip(header.size - (stream.position() - chunkStartPos));
                listener.onChunkEnd(chunkCounter, true);
              }
            } else if (eventType == 1) {
              // checkpoint event; skip for now
              stream.skip(eventSize - (stream.position() - eventStartPos));
            } else {
              long currentPos = stream.position();
              if (!listener.onEvent(eventType, stream, eventSize - (currentPos - eventStartPos))) {
                log.debug(
                    "'onEvent({}, stream)' returned false. Skipping the rest of the chunk {}",
                    eventType,
                    chunkCounter);
                // skip the rest of the chunk
                stream.skip(header.size - (stream.position() - chunkStartPos));
                listener.onChunkEnd(chunkCounter, true);
                continue;
              }
              // always skip any unconsumed event data to get the stream into consistent state
              stream.skip(eventSize - (stream.position() - eventStartPos));
            }
          }
        }
        if (!listener.onChunkEnd(chunkCounter, false)) {
          return;
        }
        chunkCounter++;
      }
    } finally {
      listener.onRecordingEnd();
    }
  }
}
