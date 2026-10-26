package datadog.trace.api.crac;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hooks run around a CRaC checkpoint/restore.
 *
 * <p>The agent registers a single {@code jdk.crac.Resource} at startup that delegates here, so
 * components can register their hooks without touching the CRaC API (the agent targets Java 8).
 * On a JVM without CRaC no checkpoint ever happens and the hooks never run.
 *
 * <p>{@link #afterRestore()} runs the {@link Phase#IDENTITY} hooks before the {@link
 * Phase#COMPONENT} hooks, so components see the restored process identity. {@link
 * #beforeCheckpoint()} runs them in reverse order.
 */
public final class CracHooks {
  private static final Logger log = LoggerFactory.getLogger(CracHooks.class);

  public enum Phase {
    /** Process identity: configuration, runtime id, pid, host, container. */
    IDENTITY,
    /** Components that read the identity: tracer, writers, profiler, metrics. */
    COMPONENT
  }

  public interface Hook {
    void beforeCheckpoint() throws Exception;

    void afterRestore() throws Exception;
  }

  private static final List<Hook> IDENTITY_HOOKS = new ArrayList<>();
  private static final List<Hook> COMPONENT_HOOKS = new ArrayList<>();
  private static volatile int restoreCount;
  private static volatile boolean checkpointing;

  private CracHooks() {}

  public static synchronized void register(Phase phase, Hook hook) {
    (phase == Phase.IDENTITY ? IDENTITY_HOOKS : COMPONENT_HOOKS).add(hook);
  }

  /**
   * Registers an action run after a restore that does not keep {@code target} alive, for objects
   * that are created more than once (e.g. per tracer instance in tests).
   */
  public static <T> void registerAfterRestore(
      Phase phase, T target, final Consumer<? super T> action) {
    final WeakReference<T> ref = new WeakReference<>(target);
    register(
        phase,
        new Hook() {
          @Override
          public void beforeCheckpoint() {}

          @Override
          public void afterRestore() {
            T current = ref.get();
            if (current != null) {
              action.accept(current);
            }
          }
        });
  }

  /** How many times this JVM was restored from a checkpoint. */
  public static int restoreCount() {
    return restoreCount;
  }

  /**
   * True from the moment the component hooks have flushed until the restore. Senders check it so
   * that no new connection is opened after the identity hooks closed them.
   */
  public static boolean isCheckpointing() {
    return checkpointing;
  }

  public static synchronized void beforeCheckpoint() {
    log.info(
        "CRaC beforeCheckpoint: {} identity and {} component hooks",
        IDENTITY_HOOKS.size(),
        COMPONENT_HOOKS.size());
    for (int i = COMPONENT_HOOKS.size() - 1; i >= 0; i--) {
      runBefore(COMPONENT_HOOKS.get(i));
    }
    checkpointing = true;
    for (int i = IDENTITY_HOOKS.size() - 1; i >= 0; i--) {
      runBefore(IDENTITY_HOOKS.get(i));
    }
  }

  public static synchronized void afterRestore() {
    // also called when the checkpoint failed: the JVM keeps running and must send again
    checkpointing = false;
    restoreCount++;
    log.info(
        "CRaC afterRestore: {} identity and {} component hooks",
        IDENTITY_HOOKS.size(),
        COMPONENT_HOOKS.size());
    for (Hook hook : IDENTITY_HOOKS) {
      runAfter(hook);
    }
    for (Hook hook : COMPONENT_HOOKS) {
      runAfter(hook);
    }
  }

  private static void runBefore(Hook hook) {
    try {
      hook.beforeCheckpoint();
    } catch (Throwable t) {
      log.warn("CRaC beforeCheckpoint hook {} failed", hook, t);
    }
  }

  private static void runAfter(Hook hook) {
    try {
      hook.afterRestore();
    } catch (Throwable t) {
      log.warn("CRaC afterRestore hook {} failed", hook, t);
    }
  }
}
