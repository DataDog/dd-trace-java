<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%--
  User: shahriarmohaiminul
  Date: 9/8/24
  Time: 1:28 PM
--%>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>

<html>
<head>
    <title>Library Update</title>
</head>
<body>
<form:form action="library/update" method="post" modelAttribute="library">
    <div class="center-container">
        <div class="content-box">
            <h2>Update Library</h2>

            <form:hidden path="id"/>

            <p>
                <span class="label">Library ID:</span>
                <span class="field-value"><c:out value="${library.id}"/></span>
            </p>

            <p>
                <span class="label">Update Count:</span>
                <span class="field-value"><c:out value="${library.updateCount}"/></span>
            </p>

            <table>
                <thead>
                <tr>
                    <th>ID</th>
                    <th>Title</th>
                    <th>Update Count</th>
                </tr>
                </thead>
                <tbody>

                <c:forEach var="book" items="${library.books}">
                    <tr>
                        <td><c:out value="${book.id}" /></td>
                        <td><c:out value="${book.title}" /></td>
                        <td><c:out value="${book.updateCount}" /></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>

            <div class="align-right">
                <button name="_action_update" type="submit" class="button">Update</button>
            </div>
        </div>
    </div>
</form:form>
</body>
</html>
