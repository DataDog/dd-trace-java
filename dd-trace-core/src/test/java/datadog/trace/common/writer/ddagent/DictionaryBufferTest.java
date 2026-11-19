package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.BufferOverflowException;
import org.junit.jupiter.api.Test;

class DictionaryBufferTest {

  @Test
  void flushingAnEmptyDictionaryDoesNothing() {
    DictionaryBuffer dictionary = new DictionaryBuffer(64);
    assertFalse(dictionary.flush());
  }

  @Test
  void flushingAFilledDictionaryEscapesAndKeepsItsEntries() {
    // traces already written refer to these codes, so the dictionary must survive the flush
    DictionaryBuffer dictionary = new DictionaryBuffer(64);
    dictionary.putInt(7);
    assertTrue(dictionary.mark());

    assertThrows(BufferOverflowException.class, dictionary::flush);
    assertEquals(1, dictionary.messageCount());
    assertEquals(7, dictionary.completedChunks().get(0).getInt());
  }

  @Test
  void aWriteThatDoesNotFitThrowsAndRewindsCleanly() {
    DictionaryBuffer dictionary = new DictionaryBuffer(8);
    dictionary.putInt(1);
    assertTrue(dictionary.mark());

    assertThrows(BufferOverflowException.class, () -> dictionary.put(new byte[16]));
    dictionary.rewind();
    assertEquals(1, dictionary.messageCount());
    assertEquals(4, dictionary.completedBytes());
  }
}
