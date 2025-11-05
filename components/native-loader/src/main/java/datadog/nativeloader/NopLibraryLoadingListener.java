package datadog.nativeloader;

import java.util.Collections;

final class NopLibraryLoadingListener extends SafeLibraryLoadingListener {
  static final NopLibraryLoadingListener INSTANCE = new NopLibraryLoadingListener();

  private NopLibraryLoadingListener() {}

  @Override
  public boolean isNop() {
    return true;
  }

  @Override
  public SafeLibraryLoadingListener join(LibraryLoadingListener listener) {
    return new CompositeLibraryLoadingListener(Collections.singletonList(listener));
  }
}
