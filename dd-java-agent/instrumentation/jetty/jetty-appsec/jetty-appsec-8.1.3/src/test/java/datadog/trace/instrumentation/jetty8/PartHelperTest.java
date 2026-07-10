package datadog.trace.instrumentation.jetty8;

import static java.util.Arrays.asList;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.http.Part;
import org.eclipse.jetty.util.MultiPartInputStream;
import org.junit.jupiter.api.Test;

class PartHelperTest {

  // ── extractFilenames ────────────────────────────────────────────────────────

  @Test
  void extractFilenamesReturnsEmptyListForNull() {
    assertEquals(emptyList(), PartHelper.extractFilenames(null));
  }

  @Test
  void extractFilenamesReturnsEmptyListForEmpty() {
    assertEquals(emptyList(), PartHelper.extractFilenames(emptyList()));
  }

  @Test
  void extractFilenamesReturnsEmptyListWhenNoPartsHaveFilename() {
    List<Part> parts = asList(formField("a"), formField("b"));
    assertEquals(emptyList(), PartHelper.extractFilenames(parts));
  }

  @Test
  void extractFilenamesExtractsFilenameFromSingleFilePart() {
    List<Part> parts = singletonList(filePart("photo.jpg"));
    assertEquals(singletonList("photo.jpg"), PartHelper.extractFilenames(parts));
  }

  @Test
  void extractFilenamesExtractsFilenamesFromMultipleFileParts() {
    List<Part> parts = asList(filePart("a.jpg"), filePart("b.png"), filePart("c.pdf"));
    assertEquals(asList("a.jpg", "b.png", "c.pdf"), PartHelper.extractFilenames(parts));
  }

  @Test
  void extractFilenamesSkipsFormFieldPartsAndKeepsFileParts() {
    List<Part> parts = asList(formField("x"), filePart("upload.zip"), formField("y"));
    assertEquals(singletonList("upload.zip"), PartHelper.extractFilenames(parts));
  }

  @Test
  void extractFilenamesPreservesFilenamesWithSpacesAndSpecialCharacters() {
    List<Part> parts = asList(filePart("my file.tar.gz"), filePart("résumé.pdf"));
    assertEquals(asList("my file.tar.gz", "résumé.pdf"), PartHelper.extractFilenames(parts));
  }

  // ── filenameFromPart ────────────────────────────────────────────────────────

  @Test
  void filenameFromPartReturnsNullWhenContentDispositionHeaderIsAbsent() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn(null);
    assertNull(PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartReturnsNullWhenThereIsNoFilenameParameter() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn("form-data; name=\"field\"");
    assertNull(PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartExtractsUnquotedFilename() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"file\"; filename=photo.jpg");
    assertEquals("photo.jpg", PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartStripsQuotesFromFilename() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"file\"; filename=\"photo.jpg\"");
    assertEquals("photo.jpg", PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartReturnsEmptyStringForEmptyQuotedFilename() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn("form-data; name=\"file\"; filename=\"\"");
    assertEquals("", PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartReturnsEmptyStringForEmptyUnquotedFilename() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn("form-data; name=\"file\"; filename=");
    assertEquals("", PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartPreservesSemicolonsInsideQuotedFilename() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"file\"; filename=\"shell;evil.php\"");
    assertEquals("shell;evil.php", PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartHandlesEscapedQuoteInsideFilename() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"file\"; filename=\"file\\\"name.txt\"");
    assertEquals("file\"name.txt", PartHelper.filenameFromPart(p));
  }

  @Test
  void filenameFromPartHandlesFilenameBeforeOtherParameters() {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; filename=\"first.txt\"; name=\"file\"");
    assertEquals("first.txt", PartHelper.filenameFromPart(p));
  }

  // ── charsetFromContentType ──────────────────────────────────────────────────

  @Test
  void charsetFromContentTypeReturnsUtf8ForNull() {
    assertEquals(StandardCharsets.UTF_8, PartHelper.charsetFromContentType(null));
  }

  @Test
  void charsetFromContentTypeReturnsUtf8WhenNoCharsetParameter() {
    assertEquals(StandardCharsets.UTF_8, PartHelper.charsetFromContentType("text/plain"));
  }

  @Test
  void charsetFromContentTypeParsesUnquotedCharset() {
    assertEquals(
        Charset.forName("ISO-8859-1"),
        PartHelper.charsetFromContentType("text/plain; charset=ISO-8859-1"));
  }

  @Test
  void charsetFromContentTypeParsesQuotedCharset() {
    assertEquals(
        Charset.forName("ISO-8859-1"),
        PartHelper.charsetFromContentType("text/plain; charset=\"ISO-8859-1\""));
  }

  @Test
  void charsetFromContentTypeIsCaseInsensitive() {
    assertEquals(
        StandardCharsets.UTF_16, PartHelper.charsetFromContentType("text/plain; CHARSET=UTF-16"));
  }

  @Test
  void charsetFromContentTypeReturnsUtf8ForUnknownCharset() {
    assertEquals(
        StandardCharsets.UTF_8,
        PartHelper.charsetFromContentType("text/plain; charset=not-a-real-charset"));
  }

  // ── extractFormFields ───────────────────────────────────────────────────────

  @Test
  void extractFormFieldsReturnsEmptyMapForNull() {
    assertEquals(Collections.emptyMap(), PartHelper.extractFormFields(null));
  }

  @Test
  void extractFormFieldsReturnsEmptyMapForEmpty() {
    assertEquals(Collections.emptyMap(), PartHelper.extractFormFields(emptyList()));
  }

  @Test
  void extractFormFieldsSkipsFileUploadParts() {
    List<Part> parts = singletonList(filePart("evil.php"));
    assertEquals(Collections.emptyMap(), PartHelper.extractFormFields(parts));
  }

  @Test
  void extractFormFieldsSkipsPartWithEmptyFilename() {
    List<Part> parts = singletonList(emptyFilenamePart("field"));
    assertEquals(Collections.emptyMap(), PartHelper.extractFormFields(parts));
  }

  @Test
  void extractFilenamesSkipsEmptyFilename() {
    List<Part> parts = singletonList(emptyFilenamePart("field"));
    assertEquals(emptyList(), PartHelper.extractFilenames(parts));
  }

  @Test
  void extractFormFieldsExtractsSingleFormField() throws IOException {
    List<Part> parts = singletonList(field("username", "alice"));
    Map<String, List<String>> expected =
        Collections.singletonMap("username", singletonList("alice"));
    assertEquals(expected, PartHelper.extractFormFields(parts));
  }

  @Test
  void extractFormFieldsGroupsMultipleValuesUnderSameName() throws IOException {
    List<Part> parts = asList(field("tag", "foo"), field("tag", "bar"));
    Map<String, List<String>> result = PartHelper.extractFormFields(parts);
    assertEquals(asList("foo", "bar"), result.get("tag"));
  }

  @Test
  void extractFormFieldsMixesFieldsAndSkipsFiles() throws IOException {
    List<Part> parts = asList(field("a", "x"), filePart("upload.bin"), field("b", "y"));
    Map<String, List<String>> result = PartHelper.extractFormFields(parts);
    assertEquals(singletonList("x"), result.get("a"));
    assertEquals(singletonList("y"), result.get("b"));
    assertNull(result.get("file"));
  }

  @Test
  void extractFormFieldsDecodesFieldUsingContentTypeCharset() throws IOException {
    byte[] iso88591Bytes = "café".getBytes("ISO-8859-1");
    List<Part> parts =
        singletonList(
            fieldWithContentType("drink", iso88591Bytes, "text/plain; charset=ISO-8859-1"));
    Map<String, List<String>> result = PartHelper.extractFormFields(parts);
    assertEquals(singletonList("café"), result.get("drink"));
  }

  @Test
  void extractFormFieldsTruncatesFieldExceedingMaxContentBytes() throws IOException {
    int maxBytes = Config.get().getAppSecMaxFileContentBytes();
    // ASCII value larger than the cap so byte length == char length and truncation is exact.
    char[] chars = new char[maxBytes * 2 + 123];
    fill(chars, 'a');
    String oversized = new String(chars);
    List<Part> parts = singletonList(field("big", oversized));
    Map<String, List<String>> result = PartHelper.extractFormFields(parts);
    List<String> values = result.get("big");
    assertEquals(1, values.size());
    assertEquals(maxBytes, values.get(0).length());
  }

  @Test
  void extractFormFieldsCapsAtMaxFileContentCount() throws IOException {
    int maxFields = Config.get().getAppSecMaxFileContentCount();
    int count = maxFields + 1;
    Part[] parts = new Part[count];
    for (int i = 0; i < count; i++) {
      parts[i] = field("field" + i, "value" + i);
    }
    Map<String, List<String>> result = PartHelper.extractFormFields(asList(parts));
    assertEquals(maxFields, result.size());
  }

  // ── getAllParts ─────────────────────────────────────────────────────────────

  @Test
  void getAllPartsReturnsEmptyListWhenBothNull() {
    assertEquals(emptyList(), PartHelper.getAllParts(null, null));
  }

  @Test
  void getAllPartsFallsBackToSingletonWhenMultiPartInputStreamIsNull() {
    Part part = filePart("evil.php");
    assertEquals(singletonList(part), PartHelper.getAllParts(null, part));
  }

  @Test
  void getAllPartsReturnsAllPartsFromMultiPartInputStream() throws Exception {
    Part file = filePart("evil.php");
    Part text = formField("name");
    MultiPartInputStream mpi = mock(MultiPartInputStream.class);
    when(mpi.getParts()).thenReturn(asList(file, text));
    assertEquals(asList(file, text), PartHelper.getAllParts(mpi, null));
  }

  @Test
  void getAllPartsPrefersFullCollectionOverSingleton() throws Exception {
    Part file = filePart("evil.php");
    Part other = formField("name");
    MultiPartInputStream mpi = mock(MultiPartInputStream.class);
    when(mpi.getParts()).thenReturn(asList(file, other));
    assertEquals(asList(file, other), PartHelper.getAllParts(mpi, file));
  }

  @Test
  void getAllPartsFallsBackToSingletonWhenGetPartsThrows() throws Exception {
    Part part = filePart("fallback.jpg");
    MultiPartInputStream mpi = mock(MultiPartInputStream.class);
    when(mpi.getParts()).thenThrow(new IOException("simulated failure"));
    assertEquals(singletonList(part), PartHelper.getAllParts(mpi, part));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Creates a stub Part that looks like a plain form field (no filename). */
  private Part field(String name, String value) throws IOException {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn("form-data; name=\"" + name + "\"");
    when(p.getName()).thenReturn(name);
    when(p.getInputStream())
        .thenReturn(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    return p;
  }

  /** Creates a stub Part with a specific Content-Type (for charset testing). */
  private Part fieldWithContentType(String name, byte[] rawValue, String contentType)
      throws IOException {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn("form-data; name=\"" + name + "\"");
    when(p.getName()).thenReturn(name);
    when(p.getContentType()).thenReturn(contentType);
    when(p.getInputStream()).thenReturn(new ByteArrayInputStream(rawValue));
    return p;
  }

  /** Creates a stub Part for extractFilenames/extractFormFields tests that only need the header. */
  private Part formField(String name) {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition")).thenReturn("form-data; name=\"" + name + "\"");
    when(p.getName()).thenReturn(name);
    return p;
  }

  /** Creates a stub Part that looks like a file upload with the given filename. */
  private Part filePart(String filename) {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"file\"; filename=\"" + filename + "\"");
    return p;
  }

  /** Creates a stub Part that has filename="" — a file input submitted with no file chosen. */
  private Part emptyFilenamePart(String name) {
    Part p = mock(Part.class);
    when(p.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"" + name + "\"; filename=\"\"");
    return p;
  }
}
