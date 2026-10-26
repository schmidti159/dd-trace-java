package datadog.trace.util;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RandomUtils {
  /** Mixed in after a CRaC restore: {@link ThreadLocalRandom} state is part of the image. */
  private static volatile long restoreSaltMsb;

  private static volatile long restoreSaltLsb;

  private RandomUtils() {}

  /** Called after a CRaC restore with values from a source the restore re-seeds. */
  public static void reseedAfterRestore(long msb, long lsb) {
    restoreSaltMsb = msb;
    restoreSaltLsb = lsb;
  }

  /** Returns a random UUID. */
  public static UUID randomUUID() {
    Random rnd = ThreadLocalRandom.current();
    long msb =
        ((rnd.nextLong() ^ restoreSaltMsb) & 0xffff_ffff_ffff_0fffL) | 0x0000_0000_0000_4000L;
    long lsb =
        ((rnd.nextLong() ^ restoreSaltLsb) & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }

  /**
   * Returns a cryptographically strong random UUID.
   *
   * <p>Note on some systems this may have a side effect of initializing java.util.logging, so its
   * use should be avoided during premain.
   */
  @SuppressForbidden
  public static UUID secureRandomUUID() {
    return UUID.randomUUID();
  }
}
