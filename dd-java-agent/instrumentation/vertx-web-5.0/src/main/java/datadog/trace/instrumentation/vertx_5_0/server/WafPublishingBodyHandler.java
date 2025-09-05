package datadog.trace.instrumentation.vertx_5_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class WafPublishingBodyHandler implements Handler<Buffer> {
  private static final Logger log = LoggerFactory.getLogger(WafPublishingBodyHandler.class);
  private static final int MAX_CONVERSION_DEPTH = 15;
  private final Handler<Buffer> delegate;

  public WafPublishingBodyHandler(Handler<Buffer> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void handle(Buffer buffer) {
    this.delegate.handle(new BufferWrapper(buffer));
  }

  static class BufferWrapper implements Buffer {
    private final Buffer delegate;
    private final AtomicBoolean hasPublished = new AtomicBoolean();

    BufferWrapper(Buffer delegate) {
      this.delegate = delegate;
    }

    private void publishRequestBody(Object body) {
      if (!hasPublished.compareAndSet(false, true)) {
        return;
      }

      AgentSpan span = AgentTracer.activeSpan();
      if (span == null) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }

      RequestContext reqCtx = span.getRequestContext();
      if (reqCtx == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, body);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction == null) {
          return;
        }
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        blockResponseFunction.tryCommitBlockingResponse(
            reqCtx.getTraceSegment(),
            rba.getStatusCode(),
            rba.getBlockingContentType(),
            rba.getExtraHeaders());
        throw new BlockingException(
            "Blocked request (for Buffer/toString or Buffer/toJson{Object,Array})");
      }
    }

    @Override
    public String toString() {
      String s = delegate.toString();
      // Process XML strings for WAF compatibility
      Object processedBody = processObjectForWaf(s);
      publishRequestBody(processedBody);
      return s;
    }

    @Override
    public String toString(String enc) {
      String s = delegate.toString(enc);
      // Process XML strings for WAF compatibility
      Object processedBody = processObjectForWaf(s);
      publishRequestBody(processedBody);
      return s;
    }

    @Override
    public String toString(Charset enc) {
      String s = delegate.toString(enc);
      // Process XML strings for WAF compatibility
      Object processedBody = processObjectForWaf(s);
      publishRequestBody(processedBody);
      return s;
    }

    @Override
    public JsonObject toJsonObject() {
      JsonObject jo = delegate.toJsonObject();
      publishRequestBody(jo.getMap());
      return jo;
    }

    @Override
    public JsonArray toJsonArray() {
      JsonArray ja = delegate.toJsonArray();
      publishRequestBody(ja.getList());
      return ja;
    }

    @Override
    public byte getByte(int pos) {
      return delegate.getByte(pos);
    }

    @Override
    public short getUnsignedByte(int pos) {
      return delegate.getUnsignedByte(pos);
    }

    @Override
    public int getInt(int pos) {
      return delegate.getInt(pos);
    }

    @Override
    public int getIntLE(int pos) {
      return delegate.getIntLE(pos);
    }

    @Override
    public long getUnsignedInt(int pos) {
      return delegate.getUnsignedInt(pos);
    }

    @Override
    public long getUnsignedIntLE(int pos) {
      return delegate.getUnsignedIntLE(pos);
    }

    @Override
    public long getLong(int pos) {
      return delegate.getLong(pos);
    }

    @Override
    public long getLongLE(int pos) {
      return delegate.getLongLE(pos);
    }

    @Override
    public double getDouble(int pos) {
      return delegate.getDouble(pos);
    }

    @Override
    public double getDoubleLE(int i) {
      return delegate.getDoubleLE(i);
    }

    @Override
    public float getFloatLE(int i) {
      return delegate.getFloatLE(i);
    }

    @Override
    public Buffer appendFloatLE(float v) {
      return delegate.appendFloatLE(v);
    }

    @Override
    public Buffer appendDoubleLE(double v) {
      return delegate.appendDoubleLE(v);
    }

    @Override
    public Buffer setDoubleLE(int i, double v) {
      return delegate.setDoubleLE(i, v);
    }

    @Override
    public Buffer setFloatLE(int i, float v) {
      return delegate.setFloatLE(i, v);
    }

    @Override
    public float getFloat(int pos) {
      return delegate.getFloat(pos);
    }

    @Override
    public short getShort(int pos) {
      return delegate.getShort(pos);
    }

    @Override
    public short getShortLE(int pos) {
      return delegate.getShortLE(pos);
    }

    @Override
    public int getUnsignedShort(int pos) {
      return delegate.getUnsignedShort(pos);
    }

    @Override
    public int getUnsignedShortLE(int pos) {
      return delegate.getUnsignedShortLE(pos);
    }

    @Override
    public int getMedium(int pos) {
      return delegate.getMedium(pos);
    }

    @Override
    public int getMediumLE(int pos) {
      return delegate.getMediumLE(pos);
    }

    @Override
    public int getUnsignedMedium(int pos) {
      return delegate.getUnsignedMedium(pos);
    }

    @Override
    public int getUnsignedMediumLE(int pos) {
      return delegate.getUnsignedMediumLE(pos);
    }

    @Override
    public byte[] getBytes() {
      return delegate.getBytes();
    }

    @Override
    public byte[] getBytes(int start, int end) {
      return delegate.getBytes(start, end);
    }

    @Override
    public Buffer getBytes(byte[] dst) {
      return delegate.getBytes(dst);
    }

    @Override
    public Buffer getBytes(byte[] dst, int dstIndex) {
      return delegate.getBytes(dst, dstIndex);
    }

    @Override
    public Buffer getBytes(int start, int end, byte[] dst) {
      return delegate.getBytes(start, end, dst);
    }

    @Override
    public Buffer getBytes(int start, int end, byte[] dst, int dstIndex) {
      return delegate.getBytes(start, end, dst, dstIndex);
    }

    @Override
    public Buffer getBuffer(int start, int end) {
      return delegate.getBuffer(start, end);
    }

    @Override
    public String getString(int start, int end, String enc) {
      return delegate.getString(start, end, enc);
    }

    @Override
    public String getString(int start, int end) {
      return delegate.getString(start, end);
    }

    @Override
    public Buffer appendBuffer(Buffer buff) {
      return delegate.appendBuffer(buff);
    }

    @Override
    public Buffer appendBuffer(Buffer buff, int offset, int len) {
      return delegate.appendBuffer(buff, offset, len);
    }

    @Override
    public Buffer appendBytes(byte[] bytes) {
      return delegate.appendBytes(bytes);
    }

    @Override
    public Buffer appendBytes(byte[] bytes, int offset, int len) {
      return delegate.appendBytes(bytes, offset, len);
    }

    @Override
    public Buffer appendByte(byte b) {
      return delegate.appendByte(b);
    }

    @Override
    public Buffer appendUnsignedByte(short b) {
      return delegate.appendUnsignedByte(b);
    }

    @Override
    public Buffer appendInt(int i) {
      return delegate.appendInt(i);
    }

    @Override
    public Buffer appendIntLE(int i) {
      return delegate.appendIntLE(i);
    }

    @Override
    public Buffer appendUnsignedInt(long i) {
      return delegate.appendUnsignedInt(i);
    }

    @Override
    public Buffer appendUnsignedIntLE(long i) {
      return delegate.appendUnsignedIntLE(i);
    }

    @Override
    public Buffer appendMedium(int i) {
      return delegate.appendMedium(i);
    }

    @Override
    public Buffer appendMediumLE(int i) {
      return delegate.appendMediumLE(i);
    }

    @Override
    public Buffer appendLong(long l) {
      return delegate.appendLong(l);
    }

    @Override
    public Buffer appendLongLE(long l) {
      return delegate.appendLongLE(l);
    }

    @Override
    public Buffer appendShort(short s) {
      return delegate.appendShort(s);
    }

    @Override
    public Buffer appendShortLE(short s) {
      return delegate.appendShortLE(s);
    }

    @Override
    public Buffer appendUnsignedShort(int s) {
      return delegate.appendUnsignedShort(s);
    }

    @Override
    public Buffer appendUnsignedShortLE(int s) {
      return delegate.appendUnsignedShortLE(s);
    }

    @Override
    public Buffer appendFloat(float f) {
      return delegate.appendFloat(f);
    }

    @Override
    public Buffer appendDouble(double d) {
      return delegate.appendDouble(d);
    }

    @Override
    public Buffer appendString(String str, String enc) {
      return delegate.appendString(str, enc);
    }

    @Override
    public Buffer appendString(String str) {
      return delegate.appendString(str);
    }

    @Override
    public Buffer setByte(int pos, byte b) {
      return delegate.setByte(pos, b);
    }

    @Override
    public Buffer setUnsignedByte(int pos, short b) {
      return delegate.setUnsignedByte(pos, b);
    }

    @Override
    public Buffer setInt(int pos, int i) {
      return delegate.setInt(pos, i);
    }

    @Override
    public Buffer setIntLE(int pos, int i) {
      return delegate.setIntLE(pos, i);
    }

    @Override
    public Buffer setUnsignedInt(int pos, long i) {
      return delegate.setUnsignedInt(pos, i);
    }

    @Override
    public Buffer setUnsignedIntLE(int pos, long i) {
      return delegate.setUnsignedIntLE(pos, i);
    }

    @Override
    public Buffer setMedium(int pos, int i) {
      return delegate.setMedium(pos, i);
    }

    @Override
    public Buffer setMediumLE(int pos, int i) {
      return delegate.setMediumLE(pos, i);
    }

    @Override
    public Buffer setLong(int pos, long l) {
      return delegate.setLong(pos, l);
    }

    @Override
    public Buffer setLongLE(int pos, long l) {
      return delegate.setLongLE(pos, l);
    }

    @Override
    public Buffer setDouble(int pos, double d) {
      return delegate.setDouble(pos, d);
    }

    @Override
    public Buffer setFloat(int pos, float f) {
      return delegate.setFloat(pos, f);
    }

    @Override
    public Buffer setShort(int pos, short s) {
      return delegate.setShort(pos, s);
    }

    @Override
    public Buffer setShortLE(int pos, short s) {
      return delegate.setShortLE(pos, s);
    }

    @Override
    public Buffer setUnsignedShort(int pos, int s) {
      return delegate.setUnsignedShort(pos, s);
    }

    @Override
    public Buffer setUnsignedShortLE(int pos, int s) {
      return delegate.setUnsignedShortLE(pos, s);
    }

    @Override
    public Buffer setBuffer(int pos, Buffer b) {
      return delegate.setBuffer(pos, b);
    }

    @Override
    public Buffer setBuffer(int pos, Buffer b, int offset, int len) {
      return delegate.setBuffer(pos, b, offset, len);
    }

    @Override
    public Buffer setBytes(int pos, ByteBuffer b) {
      return delegate.setBytes(pos, b);
    }

    @Override
    public Buffer setBytes(int pos, byte[] b) {
      return delegate.setBytes(pos, b);
    }

    @Override
    public Buffer setBytes(int pos, byte[] b, int offset, int len) {
      return delegate.setBytes(pos, b, offset, len);
    }

    @Override
    public Buffer setString(int pos, String str) {
      return delegate.setString(pos, str);
    }

    @Override
    public Buffer setString(int pos, String str, String enc) {
      return delegate.setString(pos, str, enc);
    }

    @Override
    public int length() {
      return delegate.length();
    }

    @Override
    public Buffer copy() {
      return delegate.copy();
    }

    @Override
    public Buffer slice() {
      return delegate.slice();
    }

    @Override
    public Buffer slice(int start, int end) {
      return delegate.slice(start, end);
    }

    @Override
    public void writeToBuffer(Buffer buffer) {
      delegate.writeToBuffer(buffer);
    }

    @Override
    public int readFromBuffer(int pos, Buffer buffer) {
      return delegate.readFromBuffer(pos, buffer);
    }
  }

  /** Check if a string contains XML content by looking for XML declaration or root element. */
  private static boolean isXmlContent(String content) {
    if (content == null || content.trim().isEmpty()) {
      return false;
    }
    String trimmed = content.trim();
    return trimmed.startsWith("<?xml") || (trimmed.startsWith("<") && trimmed.endsWith(">"));
  }

  /**
   * Convert XML string to WAF-compatible format. This ensures XML attack payloads are properly
   * detected by the WAF.
   */
  private static Object parseXmlToWafFormat(String xmlContent) {
    if (xmlContent == null || xmlContent.trim().isEmpty()) {
      return null;
    }

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Security settings to prevent XXE attacks during parsing
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setExpandEntityReferences(false);

      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document = builder.parse(new InputSource(new StringReader(xmlContent)));

      Element documentElement = document.getDocumentElement();
      Object converted = convertW3cNode(documentElement, MAX_CONVERSION_DEPTH);

      // Wrap in a list for consistency with other XML processing patterns
      return Collections.singletonList(converted);
    } catch (Exception e) {
      log.warn("Error parsing XML content for WAF analysis", e);
      return null;
    }
  }

  /**
   * Convert XML string to WAF-compatible format with fallback. If XML parsing fails, returns the
   * original object.
   */
  private static Object processObjectForWaf(Object obj) {
    if (obj instanceof String && isXmlContent((String) obj)) {
      Object parsed = parseXmlToWafFormat((String) obj);
      return parsed != null ? parsed : obj;
    }
    return obj;
  }

  /**
   * Convert W3C DOM Node to Map/List structure for WAF analysis. Based on Spring framework's
   * HttpMessageConverterInstrumentation.convertW3cNode() method.
   */
  private static Object convertW3cNode(org.w3c.dom.Node node, int maxRecursion) {
    if (node == null || maxRecursion <= 0) {
      return null;
    }

    if (node instanceof Element) {
      Map<String, String> attributes = Collections.emptyMap();
      if (node.hasAttributes()) {
        attributes = new HashMap<>();
        NamedNodeMap attrMap = node.getAttributes();
        for (int i = 0; i < attrMap.getLength(); i++) {
          Attr item = (Attr) attrMap.item(i);
          attributes.put(item.getName(), item.getValue());
        }
      }

      List<Object> children = Collections.emptyList();
      if (node.hasChildNodes()) {
        NodeList childNodes = node.getChildNodes();
        children = new ArrayList<>(childNodes.getLength());
        for (int i = 0; i < childNodes.getLength(); i++) {
          org.w3c.dom.Node item = childNodes.item(i);
          Object childResult = convertW3cNode(item, maxRecursion - 1);
          if (childResult != null) {
            children.add(childResult);
          }
        }
      }

      Map<String, Object> repr = new HashMap<>();
      if (!attributes.isEmpty()) {
        repr.put("attributes", attributes);
      }
      if (!children.isEmpty()) {
        repr.put("children", children);
      }
      return repr;
    } else if (node instanceof org.w3c.dom.Text) {
      String textContent = node.getTextContent();
      if (textContent != null) {
        textContent = textContent.trim();
        if (!textContent.isEmpty()) {
          return textContent;
        }
      }
    }
    return null;
  }
}
