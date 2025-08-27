package datadog.trace.instrumentation.jersey2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.ws.rs.core.Form;
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

// keep in sync with jersey3 (jakarta packages)
@AutoService(InstrumenterModule.class)
public class MessageBodyReaderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(MessageBodyReaderInstrumentation.class);
  private static final int MAX_CONVERSION_DEPTH = 15;

  public MessageBodyReaderInstrumentation() {
    super("jersey");
  }

  @Override
  public String muzzleDirective() {
    return "jersey_2";
  }

  // This is a caller for the MessageBodyReaders in jersey
  // We instrument it instead of the MessageBodyReaders in order to avoid hierarchy inspections
  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.ReaderInterceptorExecutor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("proceed").and(takesArguments(0)),
        getClass().getName() + "$ReaderInterceptorExecutorProceedAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ReaderInterceptorExecutorProceedAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return final Object ret,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (ret == null || t != null) {
        return;
      }

      if (ret.getClass()
          .getName()
          .equals("org.glassfish.jersey.media.multipart.FormDataMultiPart")) {
        // likely handled already by MultiPartReaderServerSideInstrumentation
        return;
      }

      Object objToPass;
      if (ret instanceof Form) {
        objToPass = ((Form) ret).asMap();
      } else {
        // Process XML strings for WAF compatibility
        objToPass = processObjectForWaf(ret);
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, objToPass);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockResponseFunction.tryCommitBlockingResponse(
              reqCtx.getTraceSegment(),
              rba.getStatusCode(),
              rba.getBlockingContentType(),
              rba.getExtraHeaders());
          t = new BlockingException("Blocked request (for ReaderInterceptorExecutor/proceed)");
          reqCtx.getTraceSegment().effectivelyBlocked();
        }
      }
    }
  }

  /** Check if a string contains XML content by looking for XML declaration or root element. */
  static boolean isXmlContent(String content) {
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
  static Object parseXmlToWafFormat(String xmlContent) {
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
   * Convert XML string to Map structure for testing. This method provides a simpler Map-based
   * representation compared to parseXmlToWafFormat.
   */
  static Map<String, Object> parseXmlToMap(String xmlContent) {
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
      Object converted = convertElementToMap(documentElement);

      // Return as a map with the root element name as key
      Map<String, Object> result = new HashMap<>();
      result.put(documentElement.getTagName(), converted);
      return result;
    } catch (Exception e) {
      log.warn("Error parsing XML content to Map", e);
      return null;
    }
  }

  /** Convert XML Element to Map structure for testing purposes. */
  private static Object convertElementToMap(Element element) {
    Map<String, Object> result = new HashMap<>();

    // Add attributes with @ prefix
    if (element.hasAttributes()) {
      NamedNodeMap attrs = element.getAttributes();
      for (int i = 0; i < attrs.getLength(); i++) {
        Attr attr = (Attr) attrs.item(i);
        result.put("@" + attr.getName(), attr.getValue());
      }
    }

    // Process child nodes
    NodeList children = element.getChildNodes();
    Map<String, List<Object>> childElements = new HashMap<>();
    StringBuilder textContent = new StringBuilder();

    for (int i = 0; i < children.getLength(); i++) {
      org.w3c.dom.Node child = children.item(i);

      if (child instanceof Element) {
        Element childElement = (Element) child;
        String tagName = childElement.getTagName();
        Object childValue = convertElementToMap(childElement);

        childElements.computeIfAbsent(tagName, k -> new ArrayList<>()).add(childValue);
      } else if (child instanceof org.w3c.dom.Text) {
        String text = child.getTextContent();
        if (text != null && !text.trim().isEmpty()) {
          textContent.append(text.trim());
        }
      }
    }

    // Add child elements to result
    for (Map.Entry<String, List<Object>> entry : childElements.entrySet()) {
      List<Object> values = entry.getValue();
      if (values.size() == 1) {
        result.put(entry.getKey(), values.get(0));
      } else {
        result.put(entry.getKey(), values);
      }
    }

    // Add text content if present
    String text = textContent.toString();
    if (!text.isEmpty()) {
      if (result.isEmpty()) {
        // If no child elements, return just the text
        return text;
      } else {
        // If has child elements, add text with special key
        result.put("_text", text);
      }
    }

    // If no attributes, children, or text, return empty string
    if (result.isEmpty()) {
      return "";
    }

    return result;
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
