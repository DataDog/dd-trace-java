package datadog.nativeloader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

public final class CompositeLibraryLoadingListenerTest {
  @Test
  public void onResolveDynamic() throws MalformedURLException {
    TestLibraryLoadingListener listener1 =
        new TestLibraryLoadingListener().expectResolveDynamic("foo");

    TestLibraryLoadingListener listener2 = listener1.copy();

    listeners(listener1, listener2)
        .onResolveDynamic(
            PlatformSpec.defaultPlatformSpec(), null, "foo", false, new URL("http://localhost"));

    listener1.assertDone();
    listener2.assertDone();
  }

  @Test
  public void onResolveDynamicFailure() {
    TestLibraryLoadingListener listener1 =
        new TestLibraryLoadingListener().expectResolveDynamicFailure("foo");

    TestLibraryLoadingListener listener2 = listener1.copy();

    listeners(listener1, listener2)
        .onResolveDynamicFailure(
            PlatformSpec.defaultPlatformSpec(), null, "foo", new LibraryLoadException("foo"));

    listener1.assertDone();
    listener2.assertDone();
  }

  @Test
  public void onLoad() {
    TestLibraryLoadingListener listener1 = new TestLibraryLoadingListener().expectLoad("foo");

    TestLibraryLoadingListener listener2 = listener1.copy();

    listeners(listener1, listener2)
        .onResolveDynamicFailure(
            PlatformSpec.defaultPlatformSpec(), null, "foo", new LibraryLoadException("foo"));

    listener1.assertDone();
    listener2.assertDone();
  }

  @Test
  public void onTempFileCreated() {
    TestLibraryLoadingListener listener1 =
        new TestLibraryLoadingListener().expectTempFileCreated("foo");

    TestLibraryLoadingListener listener2 = listener1.copy();

    listeners(listener1, listener2)
        .onTempFileCreated(
            PlatformSpec.defaultPlatformSpec(), null, "foo", Paths.get("/tmp/foo.dll"));

    listener1.assertDone();
    listener2.assertDone();
  }

  @Test
  public void onTempFileCreationFailure() {
    TestLibraryLoadingListener listener1 =
        new TestLibraryLoadingListener().expectTempFileCreationFailure("foo");

    TestLibraryLoadingListener listener2 = listener1.copy();

    listeners(listener1, listener2)
        .onTempFileCreationFailure(
            PlatformSpec.defaultPlatformSpec(),
            null,
            "foo",
            Paths.get("/tmp"),
            "dylib",
            null,
            new IOException("perm"));

    listener1.assertDone();
    listener2.assertDone();
  }

  @Test
  public void onTempFileCleanup() {
    TestLibraryLoadingListener listener1 =
        new TestLibraryLoadingListener().expectTempFileCleanup("foo");

    TestLibraryLoadingListener listener2 = listener1.copy();

    listeners(listener1, listener2)
        .onTempFileCleanup(
            PlatformSpec.defaultPlatformSpec(), null, "foo", Paths.get("/tmp/foo.dll"));

    listener1.assertDone();
    listener2.assertDone();
  }
  
  @Test
  public void join() {
	TestLibraryLoadingListener listener1 = new TestLibraryLoadingListener().
		expectLoad("foo");
	
	CompositeLibraryLoadingListener composite = new CompositeLibraryLoadingListener(listener1);
	assertEquals(1, composite.size());
	
	TestLibraryLoadingListener listener2 = listener1.copy();
	
	CompositeLibraryLoadingListener composite2 = composite.join(listener2);
	assertEquals(2, composite2.size());
	
	TestLibraryLoadingListener listener3 = listener1.copy();
	TestLibraryLoadingListener listener4 = listener1.copy();
	
	CompositeLibraryLoadingListener finalComposite = composite2.join(listener3, listener4);
	assertEquals(4, finalComposite.size());
	
	finalComposite.onLoad(
		PlatformSpec.defaultPlatformSpec(),
		null,
		"foo",
		false,
		Paths.get("/tmp/foo.dll"));
	
    listener1.assertDone();
    listener2.assertDone();
    listener3.assertDone();
    listener4.assertDone();
  }

  /*
   * Constructs a composite listener that includes the provided listeners
   * To test robustness...
   * - adds in additional failing listeners
   * - shuffles the order of the listeners
   */
  static CompositeLibraryLoadingListener listeners(LibraryLoadingListener... listeners) {
    List<LibraryLoadingListener> shuffledListeners = new ArrayList<>(listeners.length + 1);
    shuffledListeners.addAll(Arrays.asList(listeners));

    for (int i = 0; i < listeners.length; ++i) {
      shuffledListeners.add(new ThrowingLibraryLoadingListener());
    }

    Collections.shuffle(shuffledListeners);
    return new CompositeLibraryLoadingListener(listeners);
  }
}
