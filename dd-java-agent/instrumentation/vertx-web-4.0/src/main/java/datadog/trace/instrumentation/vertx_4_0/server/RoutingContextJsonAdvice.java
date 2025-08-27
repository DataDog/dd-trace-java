package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@RequiresRequestContext(RequestContextSlot.APPSEC)
class RoutingContextJsonAdvice {

  private static final Logger log = LoggerFactory.getLogger(RoutingContextJsonAdvice.class);
  private static final int MAX_CONVERSION_DEPTH = 15;

  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void before() {
    CallDepthThreadLocalMap.incrementCallDepth(RoutingContext.class);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Return Object obj_,
      @ActiveRequestContext RequestContext reqCtx,
      @Advice.Thrown(readOnly = false) Throwable throwable) {

    // in newer versions of vert.x rc.getBodyAsJson() calls internally rc.body().asJsonObject()
    // so we need to prevent sending the body twice to the WAF
    if (CallDepthThreadLocalMap.decrementCallDepth(RoutingContext.class) != 0) {
      return;
    }

    if (obj_ == null) {
      return;
    }
    Object obj = obj_;
    if (obj instanceof JsonObject) {
      obj = ((JsonObject) obj).getMap();
    } else {
      // Process XML strings for WAF compatibility
      obj = processObjectForWaf(obj);
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    if (callback == null) {
      return;
    }

    Flow<Void> flow = callback.apply(reqCtx, obj);
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
      if (throwable == null) {
        throwable = new BlockingException("Blocked request (for RoutingContextImpl/getBodyAsJson)");
      }
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
