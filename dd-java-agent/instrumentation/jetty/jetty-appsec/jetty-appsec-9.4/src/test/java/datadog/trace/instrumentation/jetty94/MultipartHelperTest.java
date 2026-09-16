package datadog.trace.instrumentation.jetty94;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import javax.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultipartHelperTest {

  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();
  private CallbackProvider callbackProvider;
  private RequestContext reqCtx;
  private BlockResponseFunction brf;
  private AppSecContext appSecContext;
  private TraceSegment traceSegment;

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  @BeforeEach
  void setUpBlockFailureFixtures() {
    callbackProvider = mock(CallbackProvider.class);
    traceSegment = mock(TraceSegment.class);
    brf = mock(BlockResponseFunction.class);
    appSecContext = mock(AppSecContext.class);
    reqCtx = mock(RequestContext.class);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecContext);

    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.getCallbackProvider(any(RequestContextSlot.class))).thenReturn(callbackProvider);
    AgentTracer.forceRegister(tracer);
  }

  @AfterEach
  void tearDownBlockFailureFixtures() {
    AgentTracer.forceRegister(originalTracer);
  }

  private static Flow<Void> blockingFlow() {
    return new Flow<Void>() {
      @Override
      public Action getAction() {
        return RBA;
      }

      @Override
      public Void getResult() {
        return null;
      }
    };
  }

  private <T> void stubCallback(EventType<BiFunction<RequestContext, T, Flow<Void>>> event) {
    doReturn((Object) (BiFunction<RequestContext, T, Flow<Void>>) (ctx, x) -> blockingFlow())
        .when(callbackProvider)
        .getCallback(event);
  }

  // ── fireFilenamesEvent: report-on-failure branch ───────────────────────────

  @Test
  void fireFilenamesEventReportsBlockFailureWhenCommitFails() {
    stubCallback(Events.EVENTS.requestFilesFilenames());
    when(brf.tryCommitBlockingResponse(traceSegment, RBA)).thenReturn(false);

    BlockingException result =
        MultipartHelper.fireFilenamesEvent(singletonList(part("evil.php")), reqCtx);

    assertNull(result);
    verify(appSecContext, times(1)).reportBlockFailure();
  }

  @Test
  void fireFilenamesEventDoesNotReportBlockFailureWhenCommitSucceeds() {
    stubCallback(Events.EVENTS.requestFilesFilenames());
    when(brf.tryCommitBlockingResponse(traceSegment, RBA)).thenReturn(true);

    BlockingException result =
        MultipartHelper.fireFilenamesEvent(singletonList(part("evil.php")), reqCtx);

    assertNotNull(result);
    verify(appSecContext, never()).reportBlockFailure();
  }

  @Test
  void fireFilenamesEventDoesNotReportBlockFailureWhenNoBlockResponseFunction() {
    stubCallback(Events.EVENTS.requestFilesFilenames());
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    BlockingException result =
        MultipartHelper.fireFilenamesEvent(singletonList(part("evil.php")), reqCtx);

    assertNull(result);
    verify(appSecContext, never()).reportBlockFailure();
  }

  // ── fireFilesContentEvent: report-on-failure branch ─────────────────────────

  @Test
  void fireFilesContentEventReportsBlockFailureWhenCommitFails() throws IOException {
    stubCallback(Events.EVENTS.requestFilesContent());
    when(brf.tryCommitBlockingResponse(traceSegment, RBA)).thenReturn(false);

    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("photo.jpg");
    when(p.getInputStream())
        .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

    BlockingException result = MultipartHelper.fireFilesContentEvent(singletonList(p), reqCtx);

    assertNull(result);
    verify(appSecContext, times(1)).reportBlockFailure();
  }

  @Test
  void fireFilesContentEventDoesNotReportBlockFailureWhenCommitSucceeds() throws IOException {
    stubCallback(Events.EVENTS.requestFilesContent());
    when(brf.tryCommitBlockingResponse(traceSegment, RBA)).thenReturn(true);

    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("photo.jpg");
    when(p.getInputStream())
        .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

    BlockingException result = MultipartHelper.fireFilesContentEvent(singletonList(p), reqCtx);

    assertNotNull(result);
    verify(appSecContext, never()).reportBlockFailure();
  }

  @Test
  void fireFilesContentEventDoesNotReportBlockFailureWhenNoBlockResponseFunction()
      throws IOException {
    stubCallback(Events.EVENTS.requestFilesContent());
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    Part p = mock(Part.class);
    when(p.getSubmittedFileName()).thenReturn("photo.jpg");
    when(p.getInputStream())
        .thenReturn(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

    BlockingException result = MultipartHelper.fireFilesContentEvent(singletonList(p), reqCtx);

    assertNull(result);
    verify(appSecContext, never()).reportBlockFailure();
  }

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
