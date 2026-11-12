package io.plainround.seatwave.files;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * The {@code stored_objects} table of {@code db/V1__stored_objects.sql}, through plain JDBC: the
 * rows the reconciliation job compares with the bucket, and the few writes it and the lab make.
 * Every statement is parameterized; each call opens and closes its own connection, which is enough
 * for a job that runs hourly.
 *
 * <p>Thread-safety: immutable; each call uses its own connection.
 */
public final class StoredObjectsTable {

  /** A ticket row to insert; the table's own checks refuse anything the schema forbids. */
  public record TicketRow(
      String objectKey,
      long eventId,
      UUID bookingId,
      String sha256Hex,
      long sizeBytes,
      ObjectReconciler.State state,
      Instant createdAt) {
    /** Rejects a missing field. */
    public TicketRow {
      Objects.requireNonNull(objectKey, "objectKey");
      Objects.requireNonNull(bookingId, "bookingId");
      Objects.requireNonNull(sha256Hex, "sha256Hex");
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(createdAt, "createdAt");
    }
  }

  // pgjdbc's own settings, in seconds: a stack that is down fails fast instead of hanging.
  private static final String CONNECT_TIMEOUT_SECONDS = "5";
  private static final String SOCKET_TIMEOUT_SECONDS = "30";
  private static final String TICKETS_BUCKET = "seatwave-tickets";

  private final String jdbcUrl;
  private final Properties connectionProperties = new Properties();

  /** Reads the address and the owner's credentials from the settings. */
  public StoredObjectsTable(StoreSettings settings) {
    Objects.requireNonNull(settings, "settings");
    this.jdbcUrl = settings.jdbcUrl();
    connectionProperties.setProperty("user", settings.databaseUser());
    connectionProperties.setProperty("password", settings.databasePassword());
    connectionProperties.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
    connectionProperties.setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS);
  }

  /** Inserts a ticket row; {@code object_id} is a {@code uuidv7()}, as in Module 18. */
  public void insertTicket(TicketRow row) {
    String sql =
        """
        INSERT INTO stored_objects (object_id, bucket, object_key, purpose, event_id, booking_id,
            content_type, size_bytes, sha256, state, created_at, stored_at)
        VALUES (uuidv7(), ?, ?, 'TICKET', ?, ?, 'application/pdf', ?, ?, ?, ?, ?)
        """;
    try (Connection connection = connect();
        PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setString(1, TICKETS_BUCKET);
      insert.setString(2, row.objectKey());
      insert.setLong(3, row.eventId());
      insert.setObject(4, row.bookingId());
      insert.setLong(5, row.sizeBytes());
      insert.setString(6, row.sha256Hex());
      insert.setString(7, row.state().name());
      insert.setTimestamp(8, Timestamp.from(row.createdAt()));
      // stored_has_a_time: a PENDING row has no stored_at, every other state has one.
      insert.setTimestamp(
          9,
          row.state() == ObjectReconciler.State.PENDING ? null : Timestamp.from(row.createdAt()));
      insert.executeUpdate();
    } catch (SQLException failed) {
      throw new IllegalStateException("cannot insert the row for " + row.objectKey(), failed);
    }
  }

  /** Every row of one bucket, as the reconciliation job reads them. */
  public List<ObjectReconciler.Row> rows(String bucket) {
    String sql =
        """
        SELECT object_key, state, sha256, size_bytes, created_at
        FROM stored_objects WHERE bucket = ? ORDER BY object_key
        """;
    List<ObjectReconciler.Row> rows = new ArrayList<>();
    try (Connection connection = connect();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setString(1, bucket);
      try (ResultSet found = select.executeQuery()) {
        while (found.next()) {
          rows.add(
              new ObjectReconciler.Row(
                  found.getString("object_key"),
                  ObjectReconciler.State.valueOf(found.getString("state")),
                  found.getString("sha256"),
                  found.getLong("size_bytes"),
                  found.getTimestamp("created_at").toInstant()));
        }
      }
      return List.copyOf(rows);
    } catch (SQLException failed) {
      throw new IllegalStateException("cannot read the rows of bucket " + bucket, failed);
    }
  }

  /** Whether any row names this object now, whatever its state. */
  public boolean hasRow(String bucket, String objectKey) {
    String sql = "SELECT 1 FROM stored_objects WHERE bucket = ? AND object_key = ?";
    try (Connection connection = connect();
        PreparedStatement select = connection.prepareStatement(sql)) {
      select.setString(1, bucket);
      select.setString(2, objectKey);
      try (ResultSet found = select.executeQuery()) {
        return found.next();
      }
    } catch (SQLException failed) {
      throw new IllegalStateException("cannot look up the row for " + objectKey, failed);
    }
  }

  /**
   * Removes a row only if it is still in {@code state}, so a row that changed since the job read it
   * is left alone.
   *
   * @return whether a row was removed
   */
  public boolean deleteRowIn(String bucket, String objectKey, ObjectReconciler.State state) {
    String sql = "DELETE FROM stored_objects WHERE bucket = ? AND object_key = ? AND state = ?";
    try (Connection connection = connect();
        PreparedStatement delete = connection.prepareStatement(sql)) {
      delete.setString(1, bucket);
      delete.setString(2, objectKey);
      delete.setString(3, state.name());
      return delete.executeUpdate() == 1;
    } catch (SQLException failed) {
      throw new IllegalStateException("cannot delete the row for " + objectKey, failed);
    }
  }

  /** Removes the rows under a key prefix: the lab programs and tests clean up their own rows. */
  public int deleteRowsUnder(String bucket, String keyPrefix) {
    String sql = "DELETE FROM stored_objects WHERE bucket = ? AND starts_with(object_key, ?)";
    try (Connection connection = connect();
        PreparedStatement delete = connection.prepareStatement(sql)) {
      delete.setString(1, bucket);
      delete.setString(2, keyPrefix);
      return delete.executeUpdate();
    } catch (SQLException failed) {
      throw new IllegalStateException("cannot delete the rows under " + keyPrefix, failed);
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(jdbcUrl, connectionProperties);
  }
}
