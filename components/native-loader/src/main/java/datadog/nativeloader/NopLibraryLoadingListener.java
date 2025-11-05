package datadog.nativeloader;

import java.util.Arrays;

final class NopLibraryLoadingListener extends SafeLibraryLoadingListener {
  static final NopLibraryLoadingListener INSTANCE = new NopLibraryLoadingListener();

  private NopLibraryLoadingListener() {}

  @Override
  public boolean isNop() {
    return true;
  }

  @Override
  public SafeLibraryLoadingListener join(LibraryLoadingListener... listeners) {
    return new CompositeLibraryLoadingListener(Arrays.asList(listeners));
  }
}
