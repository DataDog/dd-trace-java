package datadog.trace.core.datastreams;

import datadog.trace.api.datastreams.TransactionInfo;
import java.util.Arrays;

public class TransactionContainer {
  // we store data as an array of bytes, since the number of object can be significant
  private byte[] data;
  private int size;

  public TransactionContainer(Integer initialSizeBytes) {
    this.data = new byte[initialSizeBytes];
  }

  public void add(TransactionInfo transactionInfo) {
    // check if we need to resize
    byte[] transactionBytes = transactionInfo.getBytes();

    // resize buffer if needed
    if (data.length - size < transactionBytes.length) {
      data = Arrays.copyOf(data, data.length << 1);
    }

    // add data
    System.arraycopy(transactionBytes, 0, data, size, transactionBytes.length);
    size += transactionBytes.length;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  public void clear() {
    size = 0;
  }

  public int getSize() {
    return size;
  }

  public byte[] getData() {
    return Arrays.copyOf(data, size);
  }
}
