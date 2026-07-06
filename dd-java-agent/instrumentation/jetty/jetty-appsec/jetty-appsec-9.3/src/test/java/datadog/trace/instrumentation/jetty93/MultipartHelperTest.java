package datadog.trace.instrumentation.jetty93;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import javax.servlet.http.Part;
import org.junit.jupiter.api.Test;

class MultipartHelperTest {

  @Test
  void returnsEmptyListForNull() {
    assertEquals(emptyList(), MultipartHelper.extractFilenames(null));
  }

  @Test
  void returnsEmptyListForEmpty() {
    assertEquals(emptyList(), MultipartHelper.extractFilenames(emptyList()));
  }

  @Test
  void returnsEmptyListWhenAllPartsHaveNullFilename() {
    List<Part> parts = asList(part(null), part(null));
    assertEquals(emptyList(), MultipartHelper.extractFilenames(parts));
  }

  @Test
  void returnsEmptyListWhenAllPartsHaveEmptyFilename() {
    List<Part> parts = asList(part(""), part(""));
    assertEquals(emptyList(), MultipartHelper.extractFilenames(parts));
  }

  @Test
  void extractsFilenameFromSinglePart() {
    List<Part> parts = singletonList(part("photo.jpg"));
    assertEquals(singletonList("photo.jpg"), MultipartHelper.extractFilenames(parts));
  }

  @Test
  void extractsFilenamesFromMultipleParts() {
    List<Part> parts = asList(part("a.jpg"), part("b.png"), part("c.pdf"));
    assertEquals(asList("a.jpg", "b.png", "c.pdf"), MultipartHelper.extractFilenames(parts));
  }

  @Test
  void skipsPartsWithNullOrEmptyFilenameAndKeepsValid() {
    List<Part> parts = asList(part(null), part("valid.txt"), part(""), part("other.zip"));
    assertEquals(asList("valid.txt", "other.zip"), MultipartHelper.extractFilenames(parts));
  }

  @Test
  void preservesFilenamesWithSpacesAndSpecialCharacters() {
    List<Part> parts = asList(part("my file.tar.gz"), part("résumé.pdf"));
    assertEquals(asList("my file.tar.gz", "résumé.pdf"), MultipartHelper.extractFilenames(parts));
  }

  private Part part(String submittedFileName) {
    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn(submittedFileName);
    return p;
  }
}
