/*
 * Copyright (c) Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoft.lst_bench.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.ClearSystemProperty;
import org.junitpioneer.jupiter.SetSystemProperty;

@ClearSystemProperty(key = "lst.jdbc.diagnostics")
public class JDBCConnectionTest {
  private java.sql.Connection connection;
  private Statement statement;

  @BeforeEach
  public void setup() throws SQLException {
    connection = mock(java.sql.Connection.class);
    statement = mock(Statement.class);
    when(connection.createStatement()).thenReturn(statement);
  }

  @Test
  public void warningAndCloseFailuresDoNotReplaceExecutionFailure() throws Exception {
    SQLException primary = new SQLException("execution failure", "08S01", 10054);
    SQLException warnings = new SQLException("warning failure");
    SQLException closing = new SQLException("close failure");
    when(statement.execute(anyString())).thenThrow(primary);
    when(statement.getWarnings()).thenThrow(warnings);
    doThrow(closing).when(statement).close();

    ClientException failure =
        assertThrows(
            ClientException.class,
            () -> new JDBCConnection(connection, 0, true).execute("SELECT 1"));

    assertSame(primary, failure.getCause());
    assertEquals(2, primary.getSuppressed().length);
    assertSame(warnings, primary.getSuppressed()[0].getCause());
    assertSame(closing, primary.getSuppressed()[1]);
  }

  @Test
  public void warningsAndCleanupDoNotPreventConfiguredRetry() throws Exception {
    SQLException primary = new SQLException("execution failure");
    when(statement.execute(anyString())).thenThrow(primary).thenReturn(false);
    when(statement.getWarnings()).thenThrow(new SQLException("warning failure"));
    doThrow(new SQLException("close failure")).doNothing().when(statement).close();

    new JDBCConnection(connection, 1, false).execute("SELECT 1");

    verify(statement, times(2)).execute("SELECT 1");
    verify(statement, times(2)).close();
  }

  @Test
  public void closeFailureAfterSuccessIsReportedEvenWithNoRetries() throws Exception {
    SQLException closing = new SQLException("close failure");
    doThrow(closing).when(statement).close();

    ClientException failure =
        assertThrows(
            ClientException.class,
            () -> new JDBCConnection(connection, 0, false).execute("SELECT 1"));

    assertSame(closing, failure.getCause());
  }

  @Test
  public void successfulRetryStillReportsItsCleanupFailure() throws Exception {
    when(statement.execute(anyString()))
        .thenThrow(new SQLException("execution failure"))
        .thenReturn(false);
    SQLException closing = new SQLException("close failure");
    org.mockito.Mockito.doNothing().doThrow(closing).when(statement).close();

    ClientException failure =
        assertThrows(
            ClientException.class,
            () -> new JDBCConnection(connection, 1, false).execute("SELECT 1"));

    assertSame(closing, failure.getCause());
    verify(statement, times(2)).execute("SELECT 1");
  }

  @Test
  public void diagnosticsDisabledDoesNotReadMetadataOrChangeTimeouts() throws Exception {
    new JDBCConnection(connection, 0, false).execute("SELECT 1");

    verify(connection, never()).getMetaData();
    verify(connection, never()).getNetworkTimeout();
    verify(connection, never())
        .setNetworkTimeout(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    verify(statement, never()).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  @SetSystemProperty(key = "lst.jdbc.diagnostics", value = "true")
  public void diagnosticsReadIdentityAndMetadataWithoutChangingExecution() throws Exception {
    connection =
        mock(java.sql.Connection.class, withSettings().extraInterfaces(ConnectionIdentity.class));
    when(connection.createStatement()).thenReturn(statement);
    when(((ConnectionIdentity) connection).getClientConnectionId()).thenReturn(UUID.randomUUID());
    when(connection.getMetaData()).thenReturn(mock(DatabaseMetaData.class));

    new JDBCConnection(connection, 0, false).execute("SELECT 1");

    verify((ConnectionIdentity) connection).getClientConnectionId();
    verify(connection).getNetworkTimeout();
    verify(statement).execute("SELECT 1");
  }

  @Test
  @SetSystemProperty(key = "lst.jdbc.diagnostics", value = "true")
  public void unsupportedMetadataDoesNotPreventExecution() throws Exception {
    when(connection.getMetaData()).thenThrow(new SQLException("unsupported"));

    new JDBCConnection(connection, 0, false).execute("SELECT 1");

    verify(statement).execute("SELECT 1");
  }

  @Test
  @SetSystemProperty(key = "lst.jdbc.diagnostics", value = "true")
  public void lifecycleLogsHaveCorrelatableIdentityAndOrderedTimestamps() throws Exception {
    List<String> messages = new ArrayList<>();
    AbstractAppender appender =
        new AbstractAppender(
            "jdbc-test", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
          }
        };
    Logger logger = (Logger) LogManager.getLogger(JDBCConnection.class);
    Level originalLevel = logger.getLevel();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      UUID id = UUID.randomUUID();
      connection =
          mock(java.sql.Connection.class, withSettings().extraInterfaces(ConnectionIdentity.class));
      when(connection.createStatement()).thenReturn(statement);
      when(((ConnectionIdentity) connection).getClientConnectionId()).thenReturn(id);
      when(connection.getMetaData()).thenReturn(mock(DatabaseMetaData.class));
      JDBCConnection client = new JDBCConnection(connection, 0, false);
      client.execute("SELECT 1");
      client.close();

      Pattern shape =
          Pattern.compile(
              "JDBC_DIAGNOSTIC utc=([^ ]+) elapsedNanos=(\\d+) diagnosticId=([^ ]+) clientConnectionId=([^ ]+) pid=(\\d+) thread=(.*) attempt=(\\d+) operation=([^ ]+) boundary=([^ ]+)");
      long previous = -1;
      int boundaries = 0;
      UUID localId = null;
      for (String message : messages) {
        if (message.startsWith("JDBC_DIAGNOSTIC utc=")) {
          Matcher match = shape.matcher(message);
          assertTrue(match.matches(), message);
          Instant.parse(match.group(1));
          long elapsed = Long.parseLong(match.group(2));
          assertTrue(elapsed >= previous);
          previous = elapsed;
          UUID currentId = UUID.fromString(match.group(3));
          localId = localId == null ? currentId : localId;
          assertEquals(localId, currentId);
          assertEquals(id.toString(), match.group(4));
          assertTrue(Long.parseLong(match.group(5)) > 0);
          boundaries++;
        }
      }
      assertEquals(9, boundaries);
      assertTrue(
          messages.stream()
              .anyMatch(m -> m.endsWith("attempt=1 operation=execute boundary=enter")));
      assertTrue(
          messages.stream().anyMatch(m -> m.endsWith("attempt=1 operation=execute boundary=exit")));
    } finally {
      logger.removeAppender(appender);
      logger.setLevel(originalLevel);
      appender.stop();
    }
  }

  public interface ConnectionIdentity {
    UUID getClientConnectionId();
  }
}
