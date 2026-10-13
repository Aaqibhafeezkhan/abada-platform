package com.abada.engine.core.replay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds of a replay (E15): how many cases, how many at once, how long a lease lasts, how long a run is kept. */
@Component
public class ReplayProperties {
    @Value("${abada.replay.default-cases:20}") private int defaultCases;
    @Value("${abada.replay.max-cases:100}") private int maxCases;
    @Value("${abada.replay.max-parallel:2}") private int maxParallel;
    @Value("${abada.replay.lease-seconds:300}") private long leaseSeconds;
    @Value("${abada.replay.retention-days:30}") private int retentionDays;

    public int getDefaultCases() { return defaultCases; }
    public int getMaxCases() { return maxCases; }
    public int getMaxParallel() { return Math.max(1, maxParallel); }
    public long getLeaseSeconds() { return leaseSeconds; }
    public int getRetentionDays() { return retentionDays; }
}
