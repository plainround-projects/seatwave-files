package io.plainround.seatwave.files;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Five-year storage cost for SeatWave's ticket PDFs and organizer media under lifecycle policies,
 * at S3 list prices, and the raw capacity and rebuild traffic of a self-run store with three copies
 * against two erasure codes. Every price is from the AWS Price List API for us-east-1 (publication
 * of 9 November 2026, checked 11 November 2026); every volume is an assumption stated beside it. A
 * month is billed in full for every byte present at its start: a slight overestimate.
 */
public final class StorageCostCli {

  private static final int MONTHS = 60;
  private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);

  // S3 Standard, tiered by the month's total: first 51,200 GB, next to 512,000 GB, then the rest.
  private static final BigDecimal STANDARD_FIRST_TIER_GB = new BigDecimal("51200");
  private static final BigDecimal STANDARD_SECOND_TIER_GB = new BigDecimal("512000");
  private static final BigDecimal STANDARD_FIRST_USD = new BigDecimal("0.023");
  private static final BigDecimal STANDARD_SECOND_USD = new BigDecimal("0.022");
  private static final BigDecimal STANDARD_REST_USD = new BigDecimal("0.021");
  private static final BigDecimal INFREQUENT_USD = new BigDecimal("0.0125");
  private static final BigDecimal GLACIER_INSTANT_USD = new BigDecimal("0.004");
  private static final BigDecimal INFREQUENT_RETRIEVAL_USD_PER_GB = new BigDecimal("0.01");
  private static final BigDecimal GLACIER_INSTANT_RETRIEVAL_USD_PER_GB = new BigDecimal("0.03");
  private static final BigDecimal PUT_USD_PER_THOUSAND = new BigDecimal("0.005");
  private static final BigDecimal TO_INFREQUENT_USD_PER_THOUSAND = new BigDecimal("0.01");
  private static final BigDecimal TO_GLACIER_INSTANT_USD_PER_THOUSAND = new BigDecimal("0.02");

  // Tickets (the brief): 5,000,000 PDFs a month of 200,000 bytes, so 1,000 GB a month.
  private static final BigDecimal TICKETS_PER_MONTH = new BigDecimal("5000000");
  private static final BigDecimal TICKET_GB_PER_MONTH = new BigDecimal("1000");
  // Assumption: each month, 1 percent of the stored tickets older than a month is downloaded again.
  private static final BigDecimal OLD_TICKET_READ_SHARE = new BigDecimal("0.01");
  private static final int TICKET_EXPIRY_MONTHS = 13;

  // Media (the brief): 10 PB over 60 months, so 166,667 GB a month; average object 5 MB (assumed).
  private static final BigDecimal MEDIA_GB_PER_MONTH =
      new BigDecimal("10000000").divide(BigDecimal.valueOf(MONTHS), 6, RoundingMode.HALF_EVEN);
  private static final BigDecimal MEDIA_OBJECTS_PER_MONTH =
      MEDIA_GB_PER_MONTH
          .multiply(THOUSAND)
          .divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_EVEN);
  // Assumption: each month, 2 percent of media in a cheaper class is fetched by the CDN on a miss.
  private static final BigDecimal OLD_MEDIA_READ_SHARE = new BigDecimal("0.02");
  // Assumption: 1 percent of upload bytes belong to multipart uploads that are never completed.
  private static final BigDecimal ABANDONED_SHARE = new BigDecimal("0.01");
  private static final BigDecimal ABORT_AFTER_DAYS_SHARE_OF_MONTH =
      new BigDecimal("7").divide(new BigDecimal("30"), 6, RoundingMode.HALF_EVEN);

  // Self-run store: an all-in price per raw TB-month (drives, servers, power, space, people),
  // assumed.
  private static final BigDecimal RAW_USD_PER_TB_MONTH = new BigDecimal("4.00");
  private static final int DRIVE_TB = 20;
  private static final double REBUILD_GIGABYTES_PER_SECOND = 1.25;

  private StorageCostCli() {}

  /**
   * Prints the ticket policies, the media policies, the abandoned uploads, and the self-run
   * comparison.
   */
  public static void main(String[] arguments) {
    System.out.println(
        "TICKETS: 1,000 GB a month (5,000,000 x 200,000 bytes), 60 months, us-east-1 list prices");
    printHeader();
    printPolicy("Standard, kept 5 years", tickets(false, false));
    printPolicy("IA at 30 d, Glacier IR at 90 d, kept 5 years", tickets(true, false));
    printPolicy("IA at 30 d, Glacier IR at 90 d, expire at 13 months", tickets(true, true));
    System.out.println();
    System.out.println("MEDIA: 10 PB over 60 months (166,667 GB a month, 5 MB average object)");
    printHeader();
    printPolicy("Standard, kept 5 years", media(0, 0));
    printPolicy("IA at 90 d, kept 5 years", media(3, 0));
    printPolicy("IA at 90 d, Glacier IR at 12 months", media(3, 12));
    System.out.println();
    abandonedUploads();
    System.out.println();
    selfRun();
  }

  /** Total cost over the horizon and the bill of the last month. */
  private record Totals(BigDecimal fiveYearUsd, BigDecimal lastMonthUsd, BigDecimal lastMonthGb) {}

  private static Totals tickets(boolean isTiered, boolean doesExpire) {
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal month = BigDecimal.ZERO;
    BigDecimal stored = BigDecimal.ZERO;
    for (int monthNumber = 1; monthNumber <= MONTHS; monthNumber++) {
      int batches = doesExpire ? Math.min(monthNumber, TICKET_EXPIRY_MONTHS - 1) : monthNumber;
      // Batches aged 0 (this month), 1 and 2 months, and 3 or more months.
      int newest = Math.min(batches, 1);
      int infrequent = isTiered ? Math.min(Math.max(batches - 1, 0), 2) : 0;
      int glacier = isTiered ? Math.max(batches - 3, 0) : 0;
      int standardBatches = isTiered ? newest : batches;
      BigDecimal standardGb = TICKET_GB_PER_MONTH.multiply(BigDecimal.valueOf(standardBatches));
      BigDecimal infrequentGb = TICKET_GB_PER_MONTH.multiply(BigDecimal.valueOf(infrequent));
      BigDecimal glacierGb = TICKET_GB_PER_MONTH.multiply(BigDecimal.valueOf(glacier));
      month =
          standardCost(standardGb)
              .add(infrequentGb.multiply(INFREQUENT_USD))
              .add(glacierGb.multiply(GLACIER_INSTANT_USD))
              .add(perThousand(TICKETS_PER_MONTH, PUT_USD_PER_THOUSAND));
      if (isTiered) {
        month =
            month
                .add(
                    monthNumber >= 2
                        ? perThousand(TICKETS_PER_MONTH, TO_INFREQUENT_USD_PER_THOUSAND)
                        : BigDecimal.ZERO)
                .add(
                    monthNumber >= 4
                        ? perThousand(TICKETS_PER_MONTH, TO_GLACIER_INSTANT_USD_PER_THOUSAND)
                        : BigDecimal.ZERO)
                .add(
                    infrequentGb
                        .multiply(OLD_TICKET_READ_SHARE)
                        .multiply(INFREQUENT_RETRIEVAL_USD_PER_GB))
                .add(
                    glacierGb
                        .multiply(OLD_TICKET_READ_SHARE)
                        .multiply(GLACIER_INSTANT_RETRIEVAL_USD_PER_GB));
      }
      total = total.add(month);
      stored = standardGb.add(infrequentGb).add(glacierGb);
    }
    return new Totals(total, month, stored);
  }

  private static Totals media(int infrequentAfterMonths, int glacierAfterMonths) {
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal month = BigDecimal.ZERO;
    BigDecimal stored = BigDecimal.ZERO;
    for (int monthNumber = 1; monthNumber <= MONTHS; monthNumber++) {
      int standardBatches =
          infrequentAfterMonths == 0 ? monthNumber : Math.min(monthNumber, infrequentAfterMonths);
      int glacierBatches =
          glacierAfterMonths == 0 ? 0 : Math.max(monthNumber - glacierAfterMonths, 0);
      int infrequentBatches = monthNumber - standardBatches - glacierBatches;
      BigDecimal standardGb = MEDIA_GB_PER_MONTH.multiply(BigDecimal.valueOf(standardBatches));
      BigDecimal infrequentGb = MEDIA_GB_PER_MONTH.multiply(BigDecimal.valueOf(infrequentBatches));
      BigDecimal glacierGb = MEDIA_GB_PER_MONTH.multiply(BigDecimal.valueOf(glacierBatches));
      month =
          standardCost(standardGb)
              .add(
                  infrequentGb
                      .multiply(INFREQUENT_USD)
                      .add(
                          infrequentGb
                              .multiply(OLD_MEDIA_READ_SHARE)
                              .multiply(INFREQUENT_RETRIEVAL_USD_PER_GB)))
              .add(
                  glacierGb
                      .multiply(GLACIER_INSTANT_USD)
                      .add(
                          glacierGb
                              .multiply(OLD_MEDIA_READ_SHARE)
                              .multiply(GLACIER_INSTANT_RETRIEVAL_USD_PER_GB)))
              .add(perThousand(MEDIA_OBJECTS_PER_MONTH, PUT_USD_PER_THOUSAND));
      if (infrequentAfterMonths > 0 && monthNumber > infrequentAfterMonths) {
        month = month.add(perThousand(MEDIA_OBJECTS_PER_MONTH, TO_INFREQUENT_USD_PER_THOUSAND));
      }
      if (glacierAfterMonths > 0 && monthNumber > glacierAfterMonths) {
        month =
            month.add(perThousand(MEDIA_OBJECTS_PER_MONTH, TO_GLACIER_INSTANT_USD_PER_THOUSAND));
      }
      total = total.add(month);
      stored = standardGb.add(infrequentGb).add(glacierGb);
    }
    return new Totals(total, month, stored);
  }

  private static void abandonedUploads() {
    BigDecimal newPerMonthGb = MEDIA_GB_PER_MONTH.multiply(ABANDONED_SHARE);
    BigDecimal withoutRule = BigDecimal.ZERO;
    BigDecimal heldWithoutRule = BigDecimal.ZERO;
    for (int monthNumber = 1; monthNumber <= MONTHS; monthNumber++) {
      heldWithoutRule = newPerMonthGb.multiply(BigDecimal.valueOf(monthNumber));
      withoutRule = withoutRule.add(standardCost(heldWithoutRule));
    }
    // With the rule, each month's abandoned parts live at most 7 days: about 7/30 of a month's
    // worth.
    BigDecimal heldWithRule = newPerMonthGb.multiply(ABORT_AFTER_DAYS_SHARE_OF_MONTH);
    BigDecimal withRule = standardCost(heldWithRule).multiply(BigDecimal.valueOf(MONTHS));
    System.out.println(
        "ABANDONED MULTIPART UPLOADS: 1 percent of media upload bytes, "
            + gb(newPerMonthGb)
            + " GB a month (assumed)");
    System.out.println(
        "  no rule:          "
            + gb(heldWithoutRule)
            + " GB held at month 60, "
            + usd(withoutRule)
            + " over 5 years");
    System.out.println(
        "  abort after 7 d:  "
            + gb(heldWithRule)
            + " GB held at any time,  "
            + usd(withRule)
            + " over 5 years");
  }

  private static void selfRun() {
    BigDecimal storedTb =
        MEDIA_GB_PER_MONTH
            .multiply(BigDecimal.valueOf(MONTHS))
            .divide(THOUSAND, 6, RoundingMode.HALF_EVEN);
    System.out.println(
        "SELF-RUN STORE for the media at month 60 ("
            + gb(storedTb)
            + " TB of data), "
            + "$4.00 per raw TB-month all-in (assumed), "
            + DRIVE_TB
            + " TB drives, rebuild at 1.25 GB/s (assumed)");
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-26s %8s %10s %7s %8s %14s %12s %12s",
            "scheme",
            "overhead",
            "raw TB",
            "drives",
            "lost ok",
            "month 60",
            "rebuild read",
            "rebuild time"));
    printScheme("3 copies (replication)", 1, 2, storedTb);
    printScheme("RS(6,3), HDFS default EC", 6, 3, storedTb);
    printScheme("EC 12+4 on 16 drives", 12, 4, storedTb);
  }

  // Replication is written as "1 data + 2 extra": a lost copy is rebuilt by reading one surviving
  // copy.
  private static void printScheme(
      String name, int dataShards, int extraShards, BigDecimal storedTb) {
    BigDecimal overhead =
        BigDecimal.valueOf(dataShards + extraShards)
            .divide(BigDecimal.valueOf(dataShards), 6, RoundingMode.HALF_EVEN);
    BigDecimal rawTb = storedTb.multiply(overhead);
    long drives =
        rawTb.divide(BigDecimal.valueOf(DRIVE_TB), 0, RoundingMode.CEILING).longValueExact();
    // To rebuild one lost drive, read as many other shards as the code needs per stripe: k for RS,
    // 1 for copies.
    long rebuildReadTb = (long) DRIVE_TB * dataShards;
    double rebuildHours = rebuildReadTb * 1_000d / REBUILD_GIGABYTES_PER_SECOND / 3_600d;
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-26s %7.2fx %10s %7d %8d %14s %9d TB %10.1f h",
            name,
            overhead,
            gb(rawTb),
            drives,
            extraShards,
            usd(rawTb.multiply(RAW_USD_PER_TB_MONTH)),
            rebuildReadTb,
            rebuildHours));
  }

  private static BigDecimal standardCost(BigDecimal gigabytes) {
    BigDecimal first = gigabytes.min(STANDARD_FIRST_TIER_GB);
    BigDecimal second = gigabytes.min(STANDARD_SECOND_TIER_GB).subtract(first).max(BigDecimal.ZERO);
    BigDecimal rest = gigabytes.subtract(STANDARD_SECOND_TIER_GB).max(BigDecimal.ZERO);
    return first
        .multiply(STANDARD_FIRST_USD)
        .add(second.multiply(STANDARD_SECOND_USD))
        .add(rest.multiply(STANDARD_REST_USD));
  }

  private static BigDecimal perThousand(BigDecimal count, BigDecimal usdPerThousand) {
    return count.divide(THOUSAND, 6, RoundingMode.HALF_EVEN).multiply(usdPerThousand);
  }

  private static void printHeader() {
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-52s %16s %14s %12s",
            "policy",
            "five-year total",
            "month 60",
            "GB stored"));
  }

  private static void printPolicy(String name, Totals totals) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-52s %16s %14s %12s",
            name,
            usd(totals.fiveYearUsd()),
            usd(totals.lastMonthUsd()),
            gb(totals.lastMonthGb())));
  }

  private static String usd(BigDecimal amount) {
    return String.format(Locale.ROOT, "$%,.2f", amount.setScale(2, RoundingMode.HALF_EVEN));
  }

  private static String gb(BigDecimal amount) {
    return String.format(Locale.ROOT, "%,.0f", amount.setScale(0, RoundingMode.HALF_EVEN));
  }
}
