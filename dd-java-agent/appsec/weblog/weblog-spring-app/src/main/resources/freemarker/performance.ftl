<#ftl output_format="HTML">

<h1>Performance page</h1>

<p>This is a simple page for testing the performance of the Agent.
Sample <code>ab</code> invocation: <code>ab  -kc 100 -t 60 -r  localhost:8080/performance</code></p>

<p>It should trigger the Freemarker XSS and the SQL hooks.</p>

<p>This is the list of values retrieved from the database:<p>
<ul>
<#list values as x>
    <li>${x}</li>
</#list>
</ul>

