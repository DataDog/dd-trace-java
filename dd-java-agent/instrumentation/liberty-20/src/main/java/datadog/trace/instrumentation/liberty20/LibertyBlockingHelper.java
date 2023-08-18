package datadog.trace.instrumentation.liberty20;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import com.ibm.ws.http.channel.internal.inbound.HttpInboundServiceContextImpl;
import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.genericbnf.HeaderField;
import com.ibm.wsspi.http.channel.HttpResponseMessage;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LibertyBlockingHelper {
  private static final Logger log = LoggerFactory.getLogger(LibertyBlockingHelper.class);
  private static final WsByteBuffer[] EMPTY_BUFFER_ARRAY = new WsByteBuffer[0];

  public static BlockingException syncBufferEnter(
      HttpInboundServiceContextImpl thiz, WsByteBuffer[] buffers, AgentSpan span) {
    if (thiz.isMessageSent() || thiz.headersSent()) {
      return null;
    }

    if (span == null) {
      span = activeSpan();
    }
    if (span == null) {
      return null;
    }
    RequestContext requestContext = span.getRequestContext();
    if (requestContext == null) {
      return null;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    if (cbp == null) {
      return null;
    }

    HttpResponseMessage response = thiz.getResponse();

    BiFunction<RequestContext, Integer, Flow<Void>> respStartedCallback =
        cbp.getCallback(EVENTS.responseStarted());
    if (respStartedCallback != null) {
      respStartedCallback.apply(requestContext, response.getStatusCodeAsInt());
    }
    TriConsumer<RequestContext, String, String> headerCallback =
        cbp.getCallback(EVENTS.responseHeader());
    Function<RequestContext, Flow<Void>> headersDoneCallback =
        cbp.getCallback(EVENTS.responseHeaderDone());
    if (headerCallback == null || headersDoneCallback == null) {
      return null;
    }
    for (HeaderField hf : response.getAllHeaders()) {
      headerCallback.accept(requestContext, hf.getName(), hf.asString());
    }
    Flow<Void> flow = headersDoneCallback.apply(requestContext);
    Flow.Action action = flow.getAction();
    if (!(action instanceof Flow.Action.RequestBlockingAction)) {
      return null;
    }

    // block
    response.clear();

    Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
    response.setStatusCode(rba.getStatusCode());
    for (Map.Entry<String, String> e : rba.getExtraHeaders().entrySet()) {
      response.setHeader(e.getKey(), e.getValue());
    }

    BlockingContentType bct = rba.getBlockingContentType();
    final WsByteBuffer[] bufferArray;
    if (bct != BlockingContentType.NONE) {
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(
              bct, thiz.getRequest().getHeader("Accept").asString());
      byte[] template = BlockingActionHelper.getTemplate(type);
      response.setHeader("Content-length", Integer.toString(template.length));
      response.setHeader("Content-type", BlockingActionHelper.getContentType(type));
      WsByteBufferImpl buffer = new WsByteBufferImpl(ByteBuffer.wrap(template));
      bufferArray = new WsByteBuffer[] {buffer};
    } else {
      bufferArray = EMPTY_BUFFER_ARRAY;
    }

    BlockingException be = new BlockingException("Blocked response (syncBufferEnter)");
    try {
      thiz.reinit(thiz.getTSC()); // parsingComplete()
      thiz.finishResponseMessage(bufferArray);
    } catch (Exception e) {
      log.warn("Error committing blocking response", e);
    }

    requestContext.getTraceSegment().effectivelyBlocked();

    span.addThrowable(be);
    span.setTag(Tags.HTTP_STATUS, rba.getStatusCode());

    return be;
  }

  static class WsByteBufferImpl implements WsByteBuffer {
    final ByteBuffer bb;

    WsByteBufferImpl(ByteBuffer bb) {
      this.bb = bb;
    }

    @Override
    public boolean setBufferAction(int i) {
      return false;
    }

    @Override
    public byte[] array() {
      return this.bb.array();
    }

    @Override
    public int arrayOffset() {
      return this.bb.arrayOffset();
    }

    @Override
    public WsByteBuffer compact() {
      this.bb.compact();
      return this;
    }

    @Override
    public int compareTo(Object o) {
      ByteBuffer other = ((WsByteBuffer) o).getWrappedByteBufferNonSafe();
      return this.bb.compareTo(other);
    }

    @Override
    public char getChar() {
      return this.bb.getChar();
    }

    @Override
    public char getChar(int i) {
      return this.bb.getChar(i);
    }

    @Override
    public WsByteBuffer putChar(char c) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putChar(int i, char c) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putChar(char[] chars) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putChar(char[] chars, int i, int i1) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public double getDouble() {
      return this.bb.getDouble();
    }

    @Override
    public double getDouble(int i) {
      return this.bb.getDouble(i);
    }

    @Override
    public WsByteBuffer putDouble(double v) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putDouble(int i, double v) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public float getFloat() {
      return this.bb.getFloat();
    }

    @Override
    public float getFloat(int i) {
      return this.bb.getFloat(i);
    }

    @Override
    public WsByteBuffer putFloat(float v) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putFloat(int i, float v) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int getInt() {
      return this.bb.get();
    }

    @Override
    public int getInt(int i) {
      return this.bb.getInt(i);
    }

    @Override
    public WsByteBuffer putInt(int i) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putInt(int i, int i1) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public long getLong() {
      return this.bb.getLong();
    }

    @Override
    public long getLong(int i) {
      return this.bb.getLong(i);
    }

    @Override
    public WsByteBuffer putLong(long l) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putLong(int i, long l) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public short getShort() {
      return this.bb.getShort();
    }

    @Override
    public short getShort(int i) {
      return this.bb.getShort(i);
    }

    @Override
    public WsByteBuffer putShort(short i) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putShort(int i, short i1) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer putString(String s) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public boolean hasArray() {
      return true;
    }

    @Override
    public ByteOrder order() {
      return this.bb.order();
    }

    @Override
    public WsByteBuffer order(ByteOrder byteOrder) {
      this.bb.order(byteOrder);
      return this;
    }

    @Override
    public WsByteBuffer clear() {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int capacity() {
      return this.bb.capacity();
    }

    @Override
    public WsByteBuffer flip() {
      this.bb.flip();
      return this;
    }

    @Override
    public byte get() {
      return this.bb.get();
    }

    @Override
    public int position() {
      return this.bb.position();
    }

    @Override
    public WsByteBuffer position(int i) {
      this.bb.position(i);
      return this;
    }

    @Override
    public WsByteBuffer limit(int i) {
      this.bb.limit(i);
      return this;
    }

    @Override
    public int limit() {
      return this.bb.limit();
    }

    @Override
    public int remaining() {
      return this.bb.remaining();
    }

    @Override
    public WsByteBuffer mark() {
      this.bb.mark();
      return this;
    }

    @Override
    public WsByteBuffer reset() {
      this.bb.reset();
      return this;
    }

    @Override
    public WsByteBuffer rewind() {
      this.bb.rewind();
      return this;
    }

    @Override
    public boolean isReadOnly() {
      return true;
    }

    @Override
    public boolean hasRemaining() {
      return this.bb.hasRemaining();
    }

    @Override
    public WsByteBuffer duplicate() {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer slice() {
      return new WsByteBufferImpl(this.bb.slice());
    }

    @Override
    public WsByteBuffer get(byte[] bytes) {
      this.bb.get(bytes);
      return this;
    }

    @Override
    public WsByteBuffer get(byte[] bytes, int i, int i1) {
      this.bb.get(bytes, i, i1);
      return this;
    }

    @Override
    public byte get(int i) {
      return this.bb.get(i);
    }

    @Override
    public boolean isDirect() {
      return this.bb.isDirect();
    }

    @Override
    public WsByteBuffer put(byte b) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer put(byte[] bytes) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer put(byte[] bytes, int i, int i1) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer put(int i, byte b) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer put(ByteBuffer byteBuffer) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer put(WsByteBuffer wsByteBuffer) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public WsByteBuffer put(WsByteBuffer[] wsByteBuffers) {
      throw new UnsupportedOperationException("read-only");
    }

    @Override
    public ByteBuffer getWrappedByteBuffer() {
      return this.bb;
    }

    @Override
    public ByteBuffer getWrappedByteBufferNonSafe() {
      return this.bb;
    }

    @Override
    public void setReadOnly(boolean b) {}

    @Override
    public boolean getReadOnly() {
      return true;
    }

    @Override
    public void removeFromLeakDetection() {}

    @Override
    public void release() {}

    @Override
    public int getType() {
      return 0;
    }

    private int status = 1;

    @Override
    public int getStatus() {
      return this.status;
    }

    @Override
    public void setStatus(int i) {
      this.status = i;
    }
  }
}
