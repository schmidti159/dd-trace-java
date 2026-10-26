package datadog.trace.api;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DynamicConfigApplyAsInitialTest {

  @Test
  void withoutOverridesTheCurrentConfigBecomesTheNewInitial() {
    DynamicConfig<DynamicConfig.Snapshot> config =
        DynamicConfig.create().setTracingTags(singletonMap("env", "ckpt")).apply();

    config.initial().setTracingTags(singletonMap("env", "restore")).applyAsInitial();

    assertEquals(singletonMap("env", "restore"), config.captureTraceConfig().getTracingTags());
  }

  @Test
  void remoteOverridesAreKeptAndTakeTheNewTags() {
    DynamicConfig<DynamicConfig.Snapshot> config =
        DynamicConfig.create().setTracingTags(singletonMap("env", "ckpt")).apply();
    config.current().setTraceSampleRate(0.5).apply();

    config.initial().setTracingTags(singletonMap("env", "restore")).applyAsInitial();

    assertEquals(0.5, config.captureTraceConfig().getTraceSampleRate());
    assertEquals(singletonMap("env", "restore"), config.captureTraceConfig().getTracingTags());

    config.resetTraceConfig();
    assertNull(config.captureTraceConfig().getTraceSampleRate());
    assertEquals(singletonMap("env", "restore"), config.captureTraceConfig().getTracingTags());
  }

  @Test
  void remotelyOverriddenTagsAreKept() {
    DynamicConfig<DynamicConfig.Snapshot> config =
        DynamicConfig.create().setTracingTags(singletonMap("env", "ckpt")).apply();
    config.current().setTracingTags(singletonMap("team", "remote")).apply();

    config.initial().setTracingTags(singletonMap("env", "restore")).applyAsInitial();

    assertEquals(singletonMap("team", "remote"), config.captureTraceConfig().getTracingTags());
  }
}
