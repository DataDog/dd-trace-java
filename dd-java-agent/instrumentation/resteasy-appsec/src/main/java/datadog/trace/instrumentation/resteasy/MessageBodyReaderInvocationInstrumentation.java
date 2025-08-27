package datadog.trace.instrumentation.resteasy;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
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

@AutoService(InstrumenterModule.class)
public class MessageBodyReaderInvocationInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final Logger log =
      LoggerFactory.getLogger(MessageBodyReaderInvocationInstrumentation.class);
  private static final int MAX_CONVERSION_DEPTH = 15;

  public MessageBodyReaderInvocationInstrumentation() {
    super("resteasy");
  }

  @Override
  public String muzzleDirective() {
    return "jaxrs";
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"org.jboss.resteasy.core.interception.AbstractReaderInterceptorContext"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("readFrom")
            .and(takesArguments(1))
            .and(takesArgument(0, nameEndsWith(".MessageBodyReader"))),
        MessageBodyReaderInvocationInstrumentation.class.getName()
            + "$AbstractReaderInterceptorAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class AbstractReaderInterceptorAdvice {
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
          .equals("org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInputImpl")) {
        // already handled in MultipartFormDataReaderInstrumentation
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestBodyProcessed());
      if (callback == null) {
        return;
      }

      Flow<Void> flow = callback.apply(reqCtx, ret);
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
          t =
              new BlockingException(
                  "Blocked request (for AbstractReaderInterceptorContext/readFrom)");
          reqCtx.getTraceSegment().effectivelyBlocked();
        }
      }
    }
  }

  /** Check if an object contains XML content by examining both strings and DOM objects. */
  private static boolean isXmlContent(Object obj) {
    if (obj == null) {
      return false;
    }

    // Check for W3C DOM XML objects
    if (obj instanceof Document || obj instanceof Element || obj instanceof org.w3c.dom.Node) {
      return true;
    }

    // Check for XML string content
    if (obj instanceof String) {
      String content = (String) obj;
      if (content.trim().isEmpty()) {
        return false;
      }
      String trimmed = content.trim();

      // Explicitly exclude JSON content
      if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        return false;
      }

      // Check for XML declaration
      if (trimmed.startsWith("<?xml")) {
        return true;
      }

      // Check for XML element (must start with < and end with >, and contain at least one closing
      // tag)
      if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.contains("</")) {
        return true;
      }
    }

    return false;
  }

  /**
   * Process XML content (strings or DOM objects) for WAF compatibility. This ensures XML attack
   * payloads are properly detected by the WAF.
   */
  private static Object processXmlForWaf(Object xmlObj) {
    if (xmlObj == null) {
      return null;
    }

    // Handle W3C DOM objects directly
    if (xmlObj instanceof Document) {
      Document doc = (Document) xmlObj;
      Element documentElement = doc.getDocumentElement();
      if (documentElement != null) {
        Object converted = convertW3cNode(documentElement, MAX_CONVERSION_DEPTH);
        return Collections.singletonList(converted);
      }
      return null;
    }

    if (xmlObj instanceof Element) {
      Object converted = convertW3cNode((Element) xmlObj, MAX_CONVERSION_DEPTH);
      return Collections.singletonList(converted);
    }

    if (xmlObj instanceof org.w3c.dom.Node) {
      Object converted = convertW3cNode((org.w3c.dom.Node) xmlObj, MAX_CONVERSION_DEPTH);
      return Collections.singletonList(converted);
    }

    // Handle XML strings by parsing them first
    if (xmlObj instanceof String) {
      return parseXmlStringToWafFormat((String) xmlObj);
    }

    return null;
  }

  /**
   * Convert XML string to WAF-compatible format. This ensures XML attack payloads are properly
   * detected by the WAF.
   */
  private static Object parseXmlStringToWafFormat(String xmlContent) {
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
