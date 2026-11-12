package io.plainround.seatwave.files;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Four checks on stored bytes: content addressing (same bytes, same key), a flipped bit caught on
 * read, the collision odds of a short and a full hash name, and a stripe of four data shards and
 * one XOR parity shard that rebuilds a lost shard and, with per-shard checksums, a corrupted one.
 */
public final class IntegrityDemoCli {

  private static final int POSTER_BYTES = 300_000;
  private static final int TICKET_BYTES = 200_000;
  // Fixed seeds, so every run hashes the same bytes and prints the same keys.
  private static final long POSTER_SEED = 17L;
  private static final long TICKET_SEED = 18L;
  private static final int DATA_SHARDS = 4;
  private static final int FLIPPED_BYTE = 150_000;
  // Objects named by hash after five years of uploads, an assumption for the collision estimate.
  private static final double OBJECTS = 10_000_000d;
  private static final int SHORT_NAME_BITS = 48;
  private static final int FULL_NAME_BITS = 256;
  private static final int LOST_SHARD = 2;
  private static final int CORRUPTED_SHARD = 1;

  private IntegrityDemoCli() {}

  /** Runs the four checks and prints what each one shows. */
  public static void main(String[] arguments) {
    byte[] poster = seeded(POSTER_SEED, POSTER_BYTES);
    contentAddressing(poster);
    flippedBit(poster);
    collisionOdds();
    parityStripe(seeded(TICKET_SEED, TICKET_BYTES));
  }

  private static void contentAddressing(byte[] poster) {
    System.out.println("1. Content addressing");
    Map<String, byte[]> bucket = new HashMap<>();
    // Two organizers upload the same poster file; one byte differs in a third upload.
    byte[] sameFileAgain = poster.clone();
    byte[] editedFile = poster.clone();
    editedFile[0] ^= 1;
    for (byte[] upload : List.of(poster, sameFileAgain, editedFile)) {
      bucket.putIfAbsent(ContentAddress.keyFor("posters/1", upload, "jpg"), upload);
    }
    System.out.println(
        "   key of the poster:          " + ContentAddress.keyFor("posters/1", poster, "jpg"));
    System.out.println(
        "   key of the same file again: "
            + ContentAddress.keyFor("posters/1", sameFileAgain, "jpg"));
    System.out.println(
        "   key after one byte changed: " + ContentAddress.keyFor("posters/1", editedFile, "jpg"));
    System.out.println(
        "   uploads 3, objects stored "
            + bucket.size()
            + "; CDN name (Module 17): "
            + ContentAddress.cdnName("poster-arena", poster, "jpg"));
  }

  private static void flippedBit(byte[] poster) {
    System.out.println("2. A flipped bit");
    String key = ContentAddress.keyFor("posters/1", poster, "jpg");
    byte[] readBack = poster.clone();
    readBack[FLIPPED_BYTE] ^= (byte) 0b1000_0000;
    System.out.println("   intact copy matches its key:  " + ContentAddress.matches(key, poster));
    System.out.println(
        "   copy with bit 7 of byte "
            + String.format(Locale.ROOT, "%,d", FLIPPED_BYTE)
            + " flipped matches its key: "
            + ContentAddress.matches(key, readBack));
  }

  private static void collisionOdds() {
    System.out.println(
        "3. Collision odds for "
            + String.format(Locale.ROOT, "%,.0f", OBJECTS)
            + " objects (birthday estimate n^2 / 2^(b+1))");
    System.out.println(
        String.format(
            Locale.ROOT,
            "   %d-bit name (12 hex digits): %.3f",
            SHORT_NAME_BITS,
            birthday(SHORT_NAME_BITS)));
    System.out.println(
        String.format(
            Locale.ROOT,
            "   %d-bit name (64 hex digits): %.1e",
            FULL_NAME_BITS,
            birthday(FULL_NAME_BITS)));
  }

  private static double birthday(int bits) {
    return OBJECTS * OBJECTS / Math.pow(2, bits + 1);
  }

  private static void parityStripe(byte[] ticket) {
    System.out.println(
        "4. A stripe of "
            + DATA_SHARDS
            + " data shards and 1 XOR parity shard, for a "
            + String.format(Locale.ROOT, "%,d", ticket.length)
            + "-byte ticket PDF");
    String key = ContentAddress.keyFor("tickets/1", ticket, "pdf");
    List<byte[]> shards = stripe(ticket);
    // One checksum per shard, kept beside it: parity says "something is wrong", the checksum says
    // where.
    List<String> shardChecksums = shards.stream().map(ContentAddress::sha256Hex).toList();
    int shardBytes = shards.getFirst().length;
    System.out.println(
        String.format(
            Locale.ROOT,
            "   stored %,d bytes for %,d (%.2fx); three copies would store %,d (3.00x)",
            shardBytes * shards.size(),
            ticket.length,
            (double) shardBytes * shards.size() / ticket.length,
            3 * ticket.length));

    List<byte[]> afterDriveLoss = new ArrayList<>(shards);
    afterDriveLoss.set(LOST_SHARD, new byte[0]);
    byte[] rebuilt = rebuild(afterDriveLoss, LOST_SHARD);
    System.out.println(
        String.format(
            Locale.ROOT,
            "   shard %d of 5 lost with its drive: read %,d bytes from the other %d shards to"
                + " rebuild %,d; rebuilt shard checksum matches: %s",
            LOST_SHARD + 1,
            shardBytes * DATA_SHARDS,
            DATA_SHARDS,
            shardBytes,
            ContentAddress.sha256Hex(rebuilt).equals(shardChecksums.get(LOST_SHARD))));
    afterDriveLoss.set(LOST_SHARD, rebuilt);
    System.out.println(
        "   object reassembled from the stripe matches its key: "
            + ContentAddress.matches(key, join(afterDriveLoss)));

    List<byte[]> afterBitRot = new ArrayList<>(shards);
    byte[] rotted = shards.get(CORRUPTED_SHARD).clone();
    rotted[0] ^= 1;
    afterBitRot.set(CORRUPTED_SHARD, rotted);
    System.out.println(
        "   one bit flipped in shard "
            + (CORRUPTED_SHARD + 1)
            + ": XOR of all five shards is zero: "
            + isAllZero(xorAll(afterBitRot))
            + ", so parity sees an error but not where");
    List<Integer> badShards = new ArrayList<>();
    for (int index = 0; index < afterBitRot.size(); index++) {
      if (!ContentAddress.sha256Hex(afterBitRot.get(index)).equals(shardChecksums.get(index))) {
        badShards.add(index + 1);
      }
    }
    afterBitRot.set(CORRUPTED_SHARD, rebuild(afterBitRot, CORRUPTED_SHARD));
    System.out.println(
        "   per-shard checksums name shard "
            + badShards
            + "; after rebuilding it the object matches its key: "
            + ContentAddress.matches(key, join(afterBitRot)));
  }

  // Splits into DATA_SHARDS equal shards (zero-padded) and appends their XOR as the parity shard.
  private static List<byte[]> stripe(byte[] object) {
    int shardBytes = Math.ceilDiv(object.length, DATA_SHARDS);
    byte[] padded = Arrays.copyOf(object, shardBytes * DATA_SHARDS);
    List<byte[]> shards = new ArrayList<>();
    for (int index = 0; index < DATA_SHARDS; index++) {
      shards.add(Arrays.copyOfRange(padded, index * shardBytes, (index + 1) * shardBytes));
    }
    shards.add(xorAll(shards));
    return shards;
  }

  // Any one shard is the XOR of the other four, because the XOR of all five is zero.
  private static byte[] rebuild(List<byte[]> shards, int missing) {
    List<byte[]> others = new ArrayList<>();
    for (int index = 0; index < shards.size(); index++) {
      if (index != missing) {
        others.add(shards.get(index));
      }
    }
    return xorAll(others);
  }

  private static byte[] xorAll(List<byte[]> shards) {
    byte[] result = new byte[shards.getFirst().length];
    for (byte[] shard : shards) {
      for (int position = 0; position < result.length; position++) {
        result[position] ^= shard[position];
      }
    }
    return result;
  }

  private static boolean isAllZero(byte[] bytes) {
    for (byte value : bytes) {
      if (value != 0) {
        return false;
      }
    }
    return true;
  }

  // The data shards in order, without the parity shard and without the padding.
  private static byte[] join(List<byte[]> shards) {
    ByteArrayOutputStream joined = new ByteArrayOutputStream();
    for (int index = 0; index < DATA_SHARDS; index++) {
      joined.writeBytes(shards.get(index));
    }
    return Arrays.copyOf(joined.toByteArray(), TICKET_BYTES);
  }

  private static byte[] seeded(long seed, int length) {
    byte[] bytes = new byte[length];
    new Random(seed).nextBytes(bytes);
    return bytes;
  }
}
