package io.plainround.seatwave.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Against the running store and PostgreSQL 18.6: the job deletes only bytes no row names, never the
 * object of a STORED row, and reports a STORED row without its bytes instead of touching it.
 */
@Tag("stack")
class ReconcileJobStackTest {

  private static final String BUCKET = "seatwave-tickets";
  private static final long EVENT_ID = 2902L;
  private static final String PREFIX = "tickets/" + EVENT_ID + "/";
  private static final String PDF = "application/pdf";
  private static final int TICKET_BYTES = 200_000;
  private static final long SEED = 2902L;
  private static final Duration GRACE = Duration.ofHours(1);
  // Every object written by a test is past the grace period "as of" two hours from now.
  private static final Duration AS_OF = Duration.ofHours(2);

  private static ObjectStore store;
  private static StoredObjectsTable table;
  private static ReconcileJob job;
  private final Random random = new Random(SEED);

  @BeforeAll
  static void connect() {
    StoreSettings settings = StoreSettings.load(Path.of("")).discardError().orElseThrow();
    store = new ObjectStore(settings);
    store.createBuckets();
    table = new StoredObjectsTable(settings);
    job = new ReconcileJob(store, table, new ObjectReconciler(GRACE));
  }

  @AfterAll
  static void disconnect() {
    store.close();
  }

  @AfterEach
  void removeWhatTheTestWrote() {
    store.listing(BUCKET).stream()
        .map(ObjectReconciler.Listed::objectKey)
        .filter(objectKey -> objectKey.startsWith(PREFIX))
        .forEach(objectKey -> store.delete(BUCKET, objectKey));
    table.deleteRowsUnder(BUCKET, PREFIX);
  }

  @Test
  void neverDeletesTheObjectOfAStoredRow() {
    String key = PREFIX + UUID.randomUUID() + ".pdf";
    byte[] ticket = ticket();
    store.put(BUCKET, key, ticket, PDF);
    table.insertTicket(row(key, ticket, ObjectReconciler.State.STORED));

    var outcome = job.apply(BUCKET, Instant.now().plus(AS_OF));

    assertThat(store.exists(BUCKET, key)).isTrue();
    assertThat(outcome.deletedObjects()).doesNotContain(key);
  }

  @Test
  void deletesAnOldObjectThatNoRowNames() {
    String key = PREFIX + UUID.randomUUID() + ".pdf";
    store.put(BUCKET, key, ticket(), PDF);

    var outcome = job.apply(BUCKET, Instant.now().plus(AS_OF));

    assertThat(outcome.deletedObjects()).contains(key);
    assertThat(store.exists(BUCKET, key)).isFalse();
  }

  @Test
  void keepsAYoungObjectThatNoRowNamesYet() {
    String key = PREFIX + UUID.randomUUID() + ".pdf";
    store.put(BUCKET, key, ticket(), PDF);

    job.apply(BUCKET, Instant.now());

    assertThat(store.exists(BUCKET, key)).isTrue();
  }

  @Test
  void reportsAStoredRowWhoseBytesAreMissingAndKeepsTheRow() {
    String key = PREFIX + UUID.randomUUID() + ".pdf";
    table.insertTicket(row(key, ticket(), ObjectReconciler.State.STORED));

    var outcome = job.apply(BUCKET, Instant.now().plus(AS_OF));

    assertThat(outcome.report().missing()).contains(key);
    assertThat(table.hasRow(BUCKET, key)).isTrue();
  }

  @Test
  void reportsAStoredRowWhoseBytesDifferFromTheRecordedChecksum() {
    String key = PREFIX + UUID.randomUUID() + ".pdf";
    byte[] recorded = ticket();
    byte[] replaced = recorded.clone();
    replaced[0] ^= 1;
    table.insertTicket(row(key, recorded, ObjectReconciler.State.STORED));
    store.put(BUCKET, key, replaced, PDF);

    var outcome = job.report(BUCKET, Instant.now().plus(AS_OF));

    assertThat(outcome.report().mismatched()).contains(key);
  }

  private byte[] ticket() {
    byte[] bytes = new byte[TICKET_BYTES];
    random.nextBytes(bytes);
    return bytes;
  }

  private static StoredObjectsTable.TicketRow row(
      String key, byte[] bytes, ObjectReconciler.State state) {
    String bookingId = key.substring(PREFIX.length(), key.length() - ".pdf".length());
    return new StoredObjectsTable.TicketRow(
        key,
        EVENT_ID,
        UUID.fromString(bookingId),
        ContentAddress.sha256Hex(bytes),
        bytes.length,
        state,
        Instant.now());
  }
}
