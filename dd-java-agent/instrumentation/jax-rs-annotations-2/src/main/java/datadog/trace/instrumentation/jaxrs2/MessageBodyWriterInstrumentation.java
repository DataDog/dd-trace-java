package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
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
import javax.ws.rs.core.MediaType;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@AutoService(InstrumenterModule.class)
public class MessageBodyWriterInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final Logger log = LoggerFactory.getLogger(MessageBodyWriterInstrumentation.class);
  private static final int MAX_CONVERSION_DEPTH = 15;

  public MessageBodyWriterInstrumentation() {
    super("jax-rs");
  }

  @Override
  public String muzzleDirective() {
    return "javax-message-body-writer";
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.ws.rs.ext.MessageBodyWriter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("writeTo").and(takesArguments(7)), getClass().getName() + "$MessageBodyWriterAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class MessageBodyWriterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(
        @Advice.Argument(0) Object entity,
        @Advice.Argument(4) MediaType mediaType,
        @ActiveRequestContext RequestContext reqCtx) {

      // Handle both JSON and XML response bodies
      if (!MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType)
          && !MediaType.APPLICATION_XML_TYPE.isCompatible(mediaType)
          && !MediaType.TEXT_XML_TYPE.isCompatible(mediaType)) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Object, Flow<Void>> callback =
          cbp.getCallback(EVENTS.responseBody());
      if (callback == null) {
        return;
      }

      // Process XML entities for WAF compatibility
      Object processedEntity = processObjectForWaf(entity, mediaType);

      Flow<Void> flow = callback.apply(reqCtx, processedEntity);
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

        throw new BlockingException("Blocked request (for MessageBodyWriter)");
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

  /** Process response entity for WAF compatibility, handling both XML strings and objects. */
  private static Object processObjectForWaf(Object entity, MediaType mediaType) {
    // If it's an XML media type and the entity is a string, try to parse it
    if ((MediaType.APPLICATION_XML_TYPE.isCompatible(mediaType)
            || MediaType.TEXT_XML_TYPE.isCompatible(mediaType))
        && entity instanceof String
        && isXmlContent((String) entity)) {
      Object parsed = parseXmlToWafFormat((String) entity);
      return parsed != null ? parsed : entity;
    }
    return entity;
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
