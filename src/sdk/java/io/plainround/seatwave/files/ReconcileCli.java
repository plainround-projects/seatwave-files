package io.plainround.seatwave.files;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Section 8, Command 6: the reconciliation job against the real store and PostgreSQL. It writes
 * five cases under event 2900 (a healthy ticket, a STORED row whose bytes were never uploaded,
 * bytes with no row, a PENDING row two days old, a DELETING row whose bytes remain), runs the job
 * as a dry run and then for real, and runs it once more to show what is left for a person.
 *
 * <p>The job is run "as of" two hours from now, so every object the program just wrote is past the
 * one-hour grace period without a two-hour wait; a real job runs with the real time.
 */
public final class ReconcileCli {

  private static final String BUCKET = "seatwave-tickets";
  private static final long EVENT_ID = 2900L;
  private static final String PREFIX = "tickets/" + EVENT_ID + "/";
  private static final int TICKET_BYTES = 200_000;
  private static final long SEED = 2900L;
  // The longest URL lifetime (15 minutes) plus a margin, as in ObjectReconcilerTest.
  private static final Duration GRACE = Duration.ofHours(1);
  private static final Duration AS_OF = Duration.ofHours(2);
  private static final Duration TWO_DAYS = Duration.ofDays(2);
  private static final String PDF = "application/pdf";

  private ReconcileCli() {}

  /** Writes the cases, runs the job three times, removes what it wrote. */
  public static void main(String[] arguments) {
    StoreSettings settings = LabSettings.loadOrExit(Path.of(""));
    Random random = new Random(SEED);
    try (ObjectStore store = new ObjectStore(settings)) {
      StoredObjectsTable table = new StoredObjectsTable(settings);
      clean(store, table);
      Instant now = Instant.now();
      byte[] healthy = ticket(random);
      String healthyKey = key("019a7400-0000-7000-8000-000000000001");
      store.put(BUCKET, healthyKey, healthy, PDF);
      table.insertTicket(row(healthyKey, healthy, ObjectReconciler.State.STORED, now));
      byte[] neverUploaded = ticket(random);
      String missingKey = key("019a7400-0000-7000-8000-000000000002");
      table.insertTicket(row(missingKey, neverUploaded, ObjectReconciler.State.STORED, now));
      String orphanKey = key("019a7400-0000-7000-8000-000000000003");
      store.put(BUCKET, orphanKey, ticket(random), PDF);
      byte[] pending = ticket(random);
      String pendingKey = key("019a7400-0000-7000-8000-000000000004");
      table.insertTicket(
          row(pendingKey, pending, ObjectReconciler.State.PENDING, now.minus(TWO_DAYS)));
      byte[] deleting = ticket(random);
      String deletingKey = key("019a7400-0000-7000-8000-000000000005");
      store.put(BUCKET, deletingKey, deleting, PDF);
      table.insertTicket(row(deletingKey, deleting, ObjectReconciler.State.DELETING, now));

      ReconcileJob job = new ReconcileJob(store, table, new ObjectReconciler(GRACE));
      Instant asOf = now.plus(AS_OF);
      print("dry run", job.report(BUCKET, asOf));
      print("applied", job.apply(BUCKET, asOf));
      print("next run", job.report(BUCKET, asOf));
      System.out.println("healthy ticket still in the bucket: " + store.exists(BUCKET, healthyKey));
      clean(store, table);
    }
  }

  private static void print(String label, ReconcileJob.Outcome outcome) {
    ObjectReconciler.Report report = outcome.report();
    System.out.println(label + ":");
    System.out.println("  missing (page someone):     " + names(report.missing()));
    System.out.println("  mismatched:                 " + names(report.mismatched()));
    System.out.println("  orphans to delete:          " + names(report.orphansToDelete()));
    System.out.println("  pending to abandon:         " + names(report.pendingToAbandon()));
    System.out.println("  deletes to finish:          " + names(report.deletesToFinish()));
    if (!outcome.deletedObjects().isEmpty() || !outcome.removedRows().isEmpty()) {
      System.out.println("  objects deleted:            " + names(outcome.deletedObjects()));
      System.out.println("  rows removed:               " + names(outcome.removedRows()));
    }
  }

  // The last four digits of the booking id are enough to tell the five cases apart.
  private static String names(List<String> keys) {
    List<String> shown =
        keys.stream()
            .filter(objectKey -> objectKey.startsWith(PREFIX))
            .map(objectKey -> "..." + objectKey.substring(objectKey.length() - "0001.pdf".length()))
            .toList();
    return shown.isEmpty() ? "(none)" : String.join(" ", shown);
  }

  private static void clean(ObjectStore store, StoredObjectsTable table) {
    store.listing(BUCKET).stream()
        .map(ObjectReconciler.Listed::objectKey)
        .filter(objectKey -> objectKey.startsWith(PREFIX))
        .forEach(objectKey -> store.delete(BUCKET, objectKey));
    table.deleteRowsUnder(BUCKET, PREFIX);
  }

  private static String key(String bookingId) {
    return PREFIX + bookingId + ".pdf";
  }

  private static StoredObjectsTable.TicketRow row(
      String objectKey, byte[] bytes, ObjectReconciler.State state, Instant createdAt) {
    String bookingId = objectKey.substring(PREFIX.length(), objectKey.length() - ".pdf".length());
    return new StoredObjectsTable.TicketRow(
        objectKey,
        EVENT_ID,
        UUID.fromString(bookingId),
        ContentAddress.sha256Hex(bytes),
        bytes.length,
        state,
        createdAt);
  }

  private static byte[] ticket(Random random) {
    byte[] bytes = new byte[TICKET_BYTES];
    random.nextBytes(bytes);
    return bytes;
  }
}
