<#ftl output_format="HTML">
<h1>FreeMarker XSS</h1>
<p>Output format: ${.output_format}</P
<p>Auto-escaping: ${.auto_esc?c}</p>

<!--
DollarVariable::accept() key:
 Dollar variable is a string:
   1. write plain text
   2. output plain text with dollar format

 Dollar variable is a TemplateMarkupOutputModel (which can have plain text xor markup text):
   3. write plain text of TMOF
   4. output plain text of TMOF with dollar format
   5. write markup text of TMOF
   6. output plain text of TMOF with TMOF format
-->

<p><code>&#36;{q}</code>: ${q}</p>                 <!-- 2. -->
<#assign htmlAutoEscQ = q?esc>
<p><code>&#36;{q?esc}</code>: ${htmlAutoEscQ}</p>  <!-- 6. -->
<#assign htmlNoEscQ = q?no_esc>
<p><code>&#36;{q?no_esc}</code>: ${htmlNoEscQ}</p> <!-- 5. -->

<div style="border: 1px dotted gray">
<h3>#noautoesc</h3>
<#noautoesc>
<p><code>&#36;{q}</code>: ${q}</p>                 <!-- 1. -->
<p><code>&#36;{q?esc}</code>: ${q?esc}</p>         <!-- 6. -->
<p><code>&#36;{q?html}</code>: ${q?html}</p>       <!-- 1., but string already escaped -->
</#noautoesc>
</div>

<div style="border: 1px dotted gray">
<h3>#outputformat "RTF", #noautoescp</h3>
<#outputformat "RTF">
<#noautoesc>
<p><code>&#36;{q?esc}</code> from earlier auto escape html context (html escape is be undone and RTF escape applied):
    ${htmlAutoEscQ}</p>                            <!-- 4. -->
</#noautoesc>
</#outputformat>
</div>

<div style="border: 1px dotted gray">
<h3>#outputformat "JavaScript" (escaping not supported by FreeMarker, output format changed, not touched by sqreen)</h3>
<#outputformat "JavaScript">
<p><code>&#36;{q?esc}</code> from earlier auto escape html context (html escape is undone):
    ${htmlAutoEscQ}</p>                            <!-- 3. -->
</#outputformat>
</div>

<div style="border: 1px dotted gray">
<h3>#outputformat "undefined" (no escaping done, output format change not allowed, treated by sqreen as html context)</h3>
<#outputformat "undefined">
<p><code>&#36;{q?esc}</code> from earlier auto escape html context (undefined does not allow output format changes, so
    the original html escaping is preserved):
    ${htmlAutoEscQ}</p>                            <!-- 5. -->
 <p><code>&#36;{q?no_esc}</code> from earlier auto escape html context:
    ${htmlNoEscQ}</p>                              <!-- 6. -->
</#outputformat>
</div>
