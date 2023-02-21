package datadog.trace.api;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.IntSupplier;

/** Maps text to an integer encoding. Adapted from async-profiler. */
public class Dictionary {

  private static final int ROW_BITS = 7;
  private static final int ROWS = (1 << ROW_BITS);
  private static final int CELLS = 3;
  private static final int TABLE_CAPACITY = (ROWS * CELLS);

  private final Table table = new Table(nextBaseIndex());

  private static final AtomicIntegerFieldUpdater<Dictionary> BASE_INDEX_UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(Dictionary.class, "baseIndex");
  volatile int baseIndex;

  public int encode(CharSequence key) {
    Table table = this.table;
    int hash = key.hashCode();
    while (true) {
      int rowIndex = Math.abs(hash) % ROWS;
      Row row = table.rows[rowIndex];
      for (int c = 0; c < CELLS; c++) {
        CharSequence storedKey = row.keys.get(c);
        if (storedKey == null) {
          if (row.keys.compareAndSet(c, null, key)) {
            return table.index(rowIndex, c);
          } else {
            storedKey = row.keys.get(c);
            if (key.equals(storedKey)) {
              return table.index(rowIndex, c);
            }
          }
        }
        if (key.equals(storedKey)) {
          return table.index(rowIndex, c);
        }
      }
      table = row.getOrCreateNextTable(this::nextBaseIndex);
      hash = (hash >>> ROW_BITS) | (hash << (32 - ROW_BITS));
    }
  }

  private int nextBaseIndex() {
    return BASE_INDEX_UPDATER.addAndGet(this, TABLE_CAPACITY);
  }

  private static final class Row {

    private static final AtomicReferenceFieldUpdater<Dictionary.Row, Dictionary.Table>
        NEXT_TABLE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(Row.class, Table.class, "next");
    private final AtomicReferenceArray<CharSequence> keys = new AtomicReferenceArray<>(CELLS);
    volatile Table next;

    public Table getOrCreateNextTable(IntSupplier baseIndexSupplier) {
      Table next = this.next;
      if (next == null) {
        Table newTable = new Table(baseIndexSupplier.getAsInt());
        if (NEXT_TABLE_UPDATER.compareAndSet(this, null, newTable)) {
          next = newTable;
        }
      }
      return next;
    }
  }

  private static final class Table {

    final Row[] rows;
    final int baseIndex;

    private Table(int baseIndex) {
      this.baseIndex = baseIndex;
      this.rows = new Row[ROWS];
      Arrays.setAll(rows, i -> new Row());
    }

    int index(int row, int col) {
      return baseIndex + (col << ROW_BITS) + row;
    }
  }
}
