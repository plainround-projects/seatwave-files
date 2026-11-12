package io.plainround.seatwave.files;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The hourly reconciliation job: reads {@code stored_objects} and the bucket, sorts every
 * difference with {@link ObjectReconciler}, and acts on the safe ones. It deletes only bytes that
 * no row names (orphans past the grace period) and bytes whose row is {@code DELETING}; a {@code
 * STORED} row's object is never deleted, and a {@code STORED} row whose object is missing or
 * different is reported for a person, never "fixed".
 *
 * <p>Thread-safety: immutable; the store and table it is given are thread-safe.
 */
public final class ReconcileJob {

  /**
   * What one run found and did.
   *
   * @param report the reconciler's sorting of rows and objects
   * @param deletedObjects keys whose bytes this run removed from the bucket
   * @param removedRows keys whose rows this run removed from the table
   */
  public record Outcome(
      ObjectReconciler.Report report, List<String> deletedObjects, List<String> removedRows) {
    /** Copies the lists. */
    public Outcome {
      Objects.requireNonNull(report, "report");
      deletedObjects = List.copyOf(deletedObjects);
      removedRows = List.copyOf(removedRows);
    }
  }

  private final ObjectStore store;
  private final StoredObjectsTable table;
  private final ObjectReconciler reconciler;

  /** Uses one store, one table and the reconciler that holds the grace period. */
  public ReconcileJob(ObjectStore store, StoredObjectsTable table, ObjectReconciler reconciler) {
    this.store = Objects.requireNonNull(store, "store");
    this.table = Objects.requireNonNull(table, "table");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
  }

  /** The dry run: the report for one bucket, with nothing changed. */
  public Outcome report(String bucket, Instant now) {
    return new Outcome(compare(bucket, now), List.of(), List.of());
  }

  /** Compares, then deletes orphans and finished deletes, and drops abandoned PENDING rows. */
  public Outcome apply(String bucket, Instant now) {
    ObjectReconciler.Report report = compare(bucket, now);
    List<String> deletedObjects = new ArrayList<>();
    List<String> removedRows = new ArrayList<>();
    for (String orphan : report.orphansToDelete()) {
      // Checked again just before the delete: a row inserted since the listing (an upload whose
      // confirmation arrived late) makes the object referenced, and it stays.
      if (!table.hasRow(bucket, orphan)) {
        store.delete(bucket, orphan);
        deletedObjects.add(orphan);
      }
    }
    for (String deleting : report.deletesToFinish()) {
      // Bytes first, then the row: a crash in between leaves a DELETING row, which the next run
      // finishes, never a row pointing at nothing while it says STORED.
      store.delete(bucket, deleting);
      deletedObjects.add(deleting);
      if (table.deleteRowIn(bucket, deleting, ObjectReconciler.State.DELETING)) {
        removedRows.add(deleting);
      }
    }
    for (String pending : report.pendingToAbandon()) {
      if (table.deleteRowIn(bucket, pending, ObjectReconciler.State.PENDING)) {
        removedRows.add(pending);
      }
    }
    return new Outcome(report, deletedObjects, removedRows);
  }

  private ObjectReconciler.Report compare(String bucket, Instant now) {
    return reconciler.reconcile(table.rows(bucket), store.listing(bucket), now);
  }
}
