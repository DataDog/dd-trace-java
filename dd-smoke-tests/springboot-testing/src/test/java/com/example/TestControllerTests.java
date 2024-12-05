package com.example;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class TestControllerTests {

  @Test
  void testInsecureHash() throws NoSuchAlgorithmException {
    MessageDigest.getInstance("MD5");
    assert true;
  }

  @Test
  void testSqlInjection() throws SQLException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("username", "admin' OR '1'='1");
    String username = request.getParameter("username");
    String sql = "SELECT COUNT(*) FROM users WHERE username = '" + username + "'";

    Connection mockConnection = mock(Connection.class);
    Statement mockStatement = mock(Statement.class);
    ResultSet mockResultSet = mock(ResultSet.class);

    when(mockConnection.createStatement()).thenReturn(mockStatement);
    when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet);

    Statement statement = mockConnection.createStatement();
    ResultSet resultSet = statement.executeQuery(sql);

    assert true;
  }
}
