package datadog.trace.instrumentation.jetty94;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

  // ── extractContents ─────────────────────────────────────────────────────────

  @Test
  void extractContentsReturnsEmptyListForNull() {
    assertEquals(emptyList(), MultipartHelper.extractContents(null));
  }

  @Test
  void extractContentsReturnsEmptyListForEmpty() {
    assertEquals(emptyList(), MultipartHelper.extractContents(emptyList()));
  }

  @Test
  void extractContentsSkipsFormFieldParts() {
    List<Part> parts = asList(part(null), part(null));
    assertEquals(emptyList(), MultipartHelper.extractContents(parts));
  }

  @Test
  void extractContentsIncludesFileWithEmptyFilename() throws IOException {
    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("");
    when(p.getInputStream())
        .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
    when(p.getContentType()).thenReturn("text/plain; charset=UTF-8");
    assertEquals(singletonList("data"), MultipartHelper.extractContents(singletonList(p)));
  }

  @Test
  void extractContentsReadsFileContent() throws IOException {
    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("photo.jpg");
    when(p.getInputStream())
        .thenReturn(new ByteArrayInputStream("file-content".getBytes(StandardCharsets.UTF_8)));
    when(p.getContentType()).thenReturn("text/plain; charset=UTF-8");
    assertEquals(singletonList("file-content"), MultipartHelper.extractContents(singletonList(p)));
  }

  @Test
  void extractContentsTruncatesAtMaxContentBytes() throws IOException {
    byte[] large = new byte[MultipartHelper.MAX_CONTENT_BYTES + 1];
    Arrays.fill(large, (byte) 'A');
    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("big.bin");
    when(p.getInputStream()).thenReturn(new ByteArrayInputStream(large));
    when(p.getContentType()).thenReturn(null);
    List<String> contents = MultipartHelper.extractContents(singletonList(p));
    assertEquals(1, contents.size());
    assertEquals(MultipartHelper.MAX_CONTENT_BYTES, contents.get(0).length());
  }

  @Test
  void extractContentsReturnsEmptyStringOnIOException() throws IOException {
    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("file.txt");
    when(p.getInputStream()).thenThrow(new IOException("simulated"));
    assertEquals(singletonList(""), MultipartHelper.extractContents(singletonList(p)));
  }

  @Test
  void extractContentsCappsAtMaxFilesToInspect() throws IOException {
    int count = MultipartHelper.MAX_FILES_TO_INSPECT + 1;
    List<Part> parts = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Part p = mock(Part.class);
      when(p.getSubmittedFileName()).thenReturn("file" + i + ".txt");
      when(p.getInputStream())
          .thenReturn(new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)));
      when(p.getContentType()).thenReturn(null);
      parts.add(p);
    }
    List<String> contents = MultipartHelper.extractContents(parts);
    assertEquals(MultipartHelper.MAX_FILES_TO_INSPECT, contents.size());
  }
}
