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

import java.lang.reflect.InvocationTargetException;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A JDBC connection. */
public class JDBCConnection implements Connection {

  private static final Logger LOGGER = LoggerFactory.getLogger(JDBCConnection.class);

  private final java.sql.Connection connection;
  private final int maxNumRetries;
  private final boolean showWarnings;
  private final boolean diagnosticsEnabled = Boolean.getBoolean("lst.jdbc.diagnostics");
  private final UUID diagnosticId = UUID.randomUUID();
  private final long openedNanos = System.nanoTime();
  private String clientConnectionId = "unavailable";

  public JDBCConnection(java.sql.Connection connection, int maxNumRetries, boolean showWarnings) {
    this.connection = connection;
    this.maxNumRetries = maxNumRetries;
    this.showWarnings = showWarnings;
    if (diagnosticsEnabled) {
      logConnectionDetails();
    }
  }

  @Override
  public void execute(String sqlText) throws ClientException {
    execute(sqlText, true);
  }

  @Override
  public QueryResult executeQuery(String sqlText) throws ClientException {
    return execute(sqlText, false);
  }

  private QueryResult execute(String sqlText, boolean ignoreResults) throws ClientException {
    QueryResult queryResult = null;
    int errorCount = 0;

    // Infinite retries if number of retries is set to '-1', otherwise retry count is in addition to
    // the 1 default try, thus '<='.
    while (this.maxNumRetries == -1 || errorCount <= this.maxNumRetries) {
      Statement s = null;
      Exception executionFailure = null;
      int attempt = errorCount + 1;
      try {
        trace("createStatement", "enter", attempt);
        s = connection.createStatement();
        trace("createStatement", "exit", attempt);
        if (diagnosticsEnabled) {
          try {
            LOGGER.info(
                "JDBC_DIAGNOSTIC diagnosticId={} attempt={} queryTimeoutSeconds={}",
                diagnosticId,
                attempt,
                s.getQueryTimeout());
          } catch (SQLException e) {
            LOGGER.warn("Unable to read statement timeout; diagnosticId=" + diagnosticId, e);
          }
        }
        trace("execute", "enter", attempt);
        boolean hasResults = s.execute(sqlText);
        trace("execute", "exit", attempt);
        if (hasResults) {
          trace("results", "enter", attempt);
          ResultSet rs = s.getResultSet();
          if (ignoreResults) {
            while (rs.next()) {
              // do nothing
            }
          } else {
            queryResult = new QueryResult();
            queryResult.populate(rs);
          }
          trace("results", "exit", attempt);
        }
        // Log verbosely, if enabled.
        if (this.showWarnings && LOGGER.isWarnEnabled()) {
          trace("warnings", "enter", attempt);
          LOGGER.warn(
              createWarningString(
                  s,
                  /* prefix= */ errorCount > 0
                      ? ("Retried query, error count: " + errorCount)
                      : ""));
          trace("warnings", "exit", attempt);
        }
        // Return here if successful.
        return queryResult;
      } catch (Exception e) {
        executionFailure = e;
        queryResult = null;
        LOGGER.warn(
            "Query execution attempt {} failed; diagnosticId={}, clientConnectionId={}",
            attempt,
            diagnosticId,
            clientConnectionId,
            e);
        logSqlExceptions(e);
        String warnings = "";
        try {
          trace("errorWarnings", "enter", attempt);
          warnings = createWarningString(s, /* prefix= */ "");
          trace("errorWarnings", "exit", attempt);
        } catch (ClientException warningFailure) {
          e.addSuppressed(warningFailure);
          LOGGER.warn("Unable to collect warnings after execution failure.", warningFailure);
        }
        String lastErrorMsg =
            "Query execution attempt "
                + (errorCount + 1)
                + " unsuccessful, will retry "
                + (this.maxNumRetries - errorCount)
                + " more times; "
                + warnings
                + "stack trace: "
                + ExceptionUtils.getStackTrace(e);

        // Log execution error and any pending warnings associated with this statement, useful for
        // debugging.
        if (errorCount == this.maxNumRetries) {
          LOGGER.error(lastErrorMsg);
          throw new ClientException(lastErrorMsg, e);
        } else {
          LOGGER.warn(lastErrorMsg);
        }
        errorCount++;
      } finally {
        if (s != null) {
          try {
            trace("statement.close", "enter", attempt);
            s.close();
            trace("statement.close", "exit", attempt);
          } catch (Exception e) {
            if (executionFailure != null) {
              executionFailure.addSuppressed(e);
              LOGGER.warn("Error when closing statement after execution failure.", e);
            } else {
              throw new ClientException("Error when closing statement.", e);
            }
          }
        }
      }
    }
    // Return here if max retries reached without success
    return queryResult;
  }

  @Override
  public void close() throws ClientException {
    try {
      trace("connection.close", "enter", 0);
      connection.close();
      trace("connection.close", "exit", 0);
    } catch (SQLException e) {
      throw new ClientException(e);
    }
  }

  private String createWarningString(Statement s, String prefix) throws ClientException {
    List<String> warningList = new ArrayList<>();

    if (s != null) {
      SQLWarning warning;
      try {
        warning = s.getWarnings();
        while (warning != null) {
          warningList.add(warning.getMessage());
          warning = warning.getNextWarning();
        }
      } catch (SQLException e) {
        throw new ClientException("Error retrieving statement warnings.", e);
      }
    }

    return prefix + ";" + String.join("; ", warningList);
  }

  private void trace(String operation, String boundary, int attempt) {
    if (diagnosticsEnabled) {
      LOGGER.info(
          "JDBC_DIAGNOSTIC utc={} elapsedNanos={} diagnosticId={} clientConnectionId={} pid={} thread={} attempt={} operation={} boundary={}",
          Instant.now(),
          System.nanoTime() - openedNanos,
          diagnosticId,
          clientConnectionId,
          ProcessHandle.current().pid(),
          Thread.currentThread().getName(),
          attempt,
          operation,
          boundary);
    }
  }

  private void logConnectionDetails() {
    // Keep the SQL Server extension optional: other JDBC profiles do not include this driver.
    try {
      Object id = connection.getClass().getMethod("getClientConnectionId").invoke(connection);
      if (id instanceof UUID) {
        clientConnectionId = id.toString();
      } else {
        LOGGER.warn("JDBC connection identity unavailable; diagnosticId={}", diagnosticId);
      }
    } catch (NoSuchMethodException e) {
      LOGGER.info("Driver has no getClientConnectionId method; diagnosticId={}", diagnosticId);
    } catch (IllegalAccessException | InvocationTargetException e) {
      LOGGER.warn("Unable to read JDBC connection identity; diagnosticId=" + diagnosticId, e);
    }
    trace("connection.open", "observed", 0);
    try {
      DatabaseMetaData metadata = connection.getMetaData();
      LOGGER.info(
          "JDBC_DIAGNOSTIC diagnosticId={} driver={} driverVersion={} javaVersion={} javaVendor={} networkTimeoutMs={}",
          diagnosticId,
          metadata.getDriverName(),
          metadata.getDriverVersion(),
          System.getProperty("java.version"),
          System.getProperty("java.vendor"),
          connection.getNetworkTimeout());
    } catch (SQLException e) {
      LOGGER.warn("Unable to read JDBC diagnostic metadata; diagnosticId=" + diagnosticId, e);
    }
  }

  private void logSqlExceptions(Exception failure) {
    if (failure instanceof SQLException) {
      Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      SQLException sqlFailure = (SQLException) failure;
      while (sqlFailure != null && seen.add(sqlFailure)) {
        LOGGER.warn(
            "JDBC failure diagnosticId={} clientConnectionId={} sqlState={} vendorCode={}",
            diagnosticId,
            clientConnectionId,
            sqlFailure.getSQLState(),
            sqlFailure.getErrorCode(),
            sqlFailure);
        sqlFailure = sqlFailure.getNextException();
      }
    }
  }
}
