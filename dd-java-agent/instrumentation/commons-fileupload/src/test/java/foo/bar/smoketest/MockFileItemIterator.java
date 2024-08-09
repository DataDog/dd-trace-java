package foo.bar.smoketest;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;

public class MockFileItemIterator implements FileItemIterator {
  private final MockFileItemStream[] items;
  private int index = 0;

  public MockFileItemIterator(final MockFileItemStream... items) {
    this.items = items;
  }

  @Override
  public boolean hasNext() {
    return true;
  }

  @Override
  public FileItemStream next() {
    return items[index++];
  }
}
