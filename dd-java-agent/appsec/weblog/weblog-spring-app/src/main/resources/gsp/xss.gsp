<%@page expressionCodec="HTML" %>
<%@page taglibCodec="none" %>

<h1>Sample GSP template</h1>

Value of <code>q</code> attribute accessed via:

<ul>
    <li>&lt;%= q %&gt;: <%= q %></li>
    <li>&dollar;{q}: ${q}</li>
    <li>&dollar;{q.encodeAsRaw()}: ${"${q}".encodeAsRaw()}</li>
    <li>&dollar;{q.encodeAsHTML()}: ${q.encodeAsHTML()}</li>
    <li>&dollar;{raw(q)}: ${raw(q)}</li>
    <li>&dollar;{g.encodeAs(codec: 'Raw', q)}: ${g.encodeAs(codec: 'Raw', q)}</li>
    <!-- is actually encoded because, while the tag content is not encoded, the expression in the body is -->
    <li>&lt;g:encodeAs codec="Raw"&gt;&dollar;{q}&lt;/g:encodeAs&gt;: <g:encodeAs codec="Raw">${q}</g:encodeAs></li>
</ul>

<h2>JavaScript context</h2>

<ul>
    <li><code>document.createTextNode('&dollar;{q}')</code>: <span id="pl1"></span></li>
</ul>

<g:javascript>
    var textNode = document.createTextNode('${q}');
    document.getElementById('pl1').appendChild(textNode);
</g:javascript>

