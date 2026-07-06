<%@ page contentType="text/html;charset=UTF-8" %>
<html>
<head>
<title>Test XSS</title>
</head>
<body>
	<% String test = request.getParameter("test");%>
	<h1>Test Page</h1>
	<p>Test parameter: <%=test%></p>
</body>
</html>
