<%@ taglib uri = "http://java.sun.com/jsp/jstl/core" prefix = "c" %>
<%@ taglib uri = "http://java.sun.com/jsp/jstl/functions" prefix = "fn" %>

Value of <code>q</code> attribute accessed via:

<ul>
    <li>Scriptlet <code>&lt;%= pageContext.findAttribute("q") %&gt;: <%= pageContext.findAttribute("q") %></li>
    <li>&#36;{q}: ${q}</li>
    <li>&lt;c:out value="&#36;{q}"/&gt;: <c:out value="${q}"/></li>
    <li>&lt;c:out value="&#36;{q}" escapeXml="false" /&gt;: <c:out value="${q}" escapeXml="false"/></li>
    <li>&#36;{fn:escapeXml(q)}: ${fn:escapeXml(q)}</li>
    <li>&lt;c:out value="&#36;{fn:escapeXml(q)}"/&gt;: <c:out value="${fn:escapeXml(q)}"/></li>
</ul>

