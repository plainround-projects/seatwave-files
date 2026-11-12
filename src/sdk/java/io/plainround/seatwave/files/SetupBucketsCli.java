package io.plainround.seatwave.files;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Command 2 of Section 8: creates the four buckets on the running stack, applies the lifecycle
 * rules the lab's store takes, and shows that no bucket answers an anonymous request.
 */
public final class SetupBucketsCli {

  private SetupBucketsCli() {}

  /** Runs the setup once; a second run finds everything in place and prints the same. */
  public static void main(String[] arguments) {
    StoreSettings settings = LabSettings.loadOrExit(Path.of(""));
    try (ObjectStore store = new ObjectStore(settings);
        StoreHttp anonymous = new StoreHttp()) {
      store.createBuckets();
      store.applyLifecycleRules();
      for (String bucket : ObjectStore.BUCKETS) {
        StoreHttp.Answer listing =
            anonymous.get(settings.endpoint() + "/" + bucket + "?list-type=2");
        System.out.println(
            String.format(
                Locale.ROOT,
                "%-18s present; public: %s; anonymous LIST: %s",
                bucket,
                store.isPublic(bucket),
                listing.summary()));
      }
      for (String bucket : List.of("seatwave-media", "seatwave-tickets")) {
        System.out.println(
            "lifecycle rules of "
                + bucket
                + ": "
                + String.join(", ", store.lifecycleRuleIds(bucket)));
      }
      System.out.println(
          "left out: "
              + ObjectStore.TRANSITION_RULE
              + " (the store has no STANDARD_IA class; it refuses a transition with \"invalid"
              + " tier\")");
    }
  }
}
