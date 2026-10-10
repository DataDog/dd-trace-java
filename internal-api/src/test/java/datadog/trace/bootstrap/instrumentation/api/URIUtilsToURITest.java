package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@SuppressWarnings("deprecation") // new URL(String) is what applications use to build these URLs
class URIUtilsToURITest {

  @TableTest({
    "Scenario                  | Url                              | Expected                              ",
    "well-formed               | 'http://example.com/a/b?x=1#f'   | 'http://example.com/a/b?x=1#f'        ",
    "valid escape kept         | 'http://example.com/a%20b'       | 'http://example.com/a%20b'            ",
    "space in path             | 'http://example.com/a b'         | 'http://example.com/a%20b'            ",
    "space in query            | 'http://example.com/p?q=a b'     | 'http://example.com/p?q=a%20b'        ",
    "pipe in query             | 'http://example.com/p?q=a|b'     | 'http://example.com/p?q=a%7Cb'        ",
    "braces in path            | 'http://example.com/{id}'        | 'http://example.com/%7Bid%7D'         ",
    "quotes and angle brackets | 'http://example.com/p?q=\"<x>\"' | 'http://example.com/p?q=%22%3Cx%3E%22'",
    "invalid escape            | 'http://example.com/a%zz'        | 'http://example.com/a%25zz'           ",
    "percent at end            | 'http://example.com/100%'        | 'http://example.com/100%25'           ",
    "truncated escape          | 'http://example.com/a%4'         | 'http://example.com/a%254'            ",
    "brackets in path          | 'http://example.com/a[1]'        | 'http://example.com/a%5B1%5D'         ",
    "brackets in query kept    | 'http://example.com/p?ids[]=1'   | 'http://example.com/p?ids[]=1'        ",
    "ipv6 host kept            | 'http://[::1]:8080/x'            | 'http://[::1]:8080/x'                 ",
    "second hash               | 'http://example.com/p#a#b'       | 'http://example.com/p#a%23b'          "
  })
  void repairsUrlsThatUriRejects(String url, String expected) throws Exception {
    assertEquals(expected, URIUtils.toURI(new URL(url)).toString());
  }

  @Test
  void keepsNonAsciiLettersButEncodesNonAsciiSpaces() throws Exception {
    final char eAcute = (char) 0xE9;
    final char noBreakSpace = (char) 0xA0;
    assertEquals(
        "http://example.com/caf" + eAcute,
        URIUtils.toURI(new URL("http://example.com/caf" + eAcute)).toString());
    assertEquals(
        "http://example.com/a%C2%A0b",
        URIUtils.toURI(new URL("http://example.com/a" + noBreakSpace + "b")).toString());
  }

  @Test
  void convertsWellFormedUrlsLikeUrlToUri() throws Exception {
    for (String text :
        new String[] {
          "http://example.com",
          "https://user:pw@example.com:8443/a/b;c=d?x=1&y=2#frag",
          "http://[::1]:8080/x",
          "file:///tmp/some%20file.txt"
        }) {
      URL url = new URL(text);
      assertEquals(url.toURI(), URIUtils.toURI(url), text);
    }
  }

  @Test
  void stillThrowsForProblemsItDoesNotRepair() throws Exception {
    // the multi-argument constructor does not validate the host, so this URL has an unterminated
    // IPv6 host that java.net.URI rejects
    URL url = new URL("http", "[::1", -1, "/x");

    assertThrows(URISyntaxException.class, () -> URIUtils.toURI(url));
  }

  @Test
  void wellFormedUrlsTakeTheStrictPath() throws Exception {
    URIUtils.ToURI latch = new URIUtils.ToURI();

    assertEquals(
        "http://example.com/a", latch.tryApply(new URL("http://example.com/a")).toString());
    assertFalse(latch.isEngaged());
  }

  @Test
  void aBadUrlEngagesTheRepairAndRepairsKeepItEngaged() throws Exception {
    URIUtils.ToURI latch = new URIUtils.ToURI();

    assertEquals(
        "http://example.com/a%20b", latch.tryApply(new URL("http://example.com/a b")).toString());
    assertTrue(latch.isEngaged());

    for (int i = 0; i < URIUtils.ToURI.CLOSE_AFTER; i++) {
      latch.tryApply(new URL("http://example.com/c d"));
    }
    assertTrue(latch.isEngaged(), "repaired URLs restart the count");
  }

  @Test
  void wellFormedUrlsDisengageTheRepair() throws Exception {
    URIUtils.ToURI latch = new URIUtils.ToURI();
    latch.tryApply(new URL("http://example.com/a b"));

    for (int i = 0; i < URIUtils.ToURI.CLOSE_AFTER; i++) {
      assertEquals(
          "http://example.com/ok", latch.tryApply(new URL("http://example.com/ok")).toString());
    }
    assertFalse(latch.isEngaged());
  }

  @Test
  void anUnrepairableUrlYieldsNullFromTheLatch() throws Exception {
    URIUtils.ToURI latch = new URIUtils.ToURI();

    assertNull(latch.tryApply(new URL("http", "[::1", -1, "/x")));
    assertTrue(latch.isEngaged());
  }
}
