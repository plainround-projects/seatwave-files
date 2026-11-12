package io.plainround.seatwave.files;

/**
 * The storage side's answer to a request that presents a pre-signed URL. Every refusal is an
 * expected outcome (a stale link, a forged link, a wrong body), so it is a value, not an exception.
 */
public sealed interface UrlVerdict {

  /**
   * The request may proceed: the URL was signed by SeatWave, is unchanged, unexpired and obeyed.
   */
  record Accepted(UrlGrant grant) implements UrlVerdict {}

  /** The request is refused; {@code detail} says why in words a log line can carry. */
  record Refused(Reason reason, String detail) implements UrlVerdict {}

  /** Why a request was refused, in the order the checks run. */
  enum Reason {
    /** The URL is missing a parameter or names no bucket and key. */
    MALFORMED,
    /**
     * Some signed part (method, bucket, key, expiry, content rule) differs from what was signed.
     */
    BAD_SIGNATURE,
    /** The signature is genuine, but the expiry instant has passed. */
    EXPIRED,
    /** The signature is genuine and current, but the body differs from the signed content rule. */
    CONTENT_MISMATCH
  }
}
