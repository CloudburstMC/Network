# Lifecycle cost and throughput acceptance task

Before releasing the controlled path to a 50,000-host fleet, measure its real lifecycle database workload separately from cache-only control-frame authentication. The integration fixture reported 410 primary D1 prepare calls while completing six HTTP heartbeats plus bootstrap. That instrumentation count includes setup/guards and is not a normalized steady-state query cost or billing estimate.

The release review must:

- Attribute actual primary statements, batches, writes, read rows, duration and retries to bootstrap, heartbeat, outcomes, cancellation, key rotation and source publication.
- Measure a settled unchanged heartbeat separately from first key/profile/basis exchange, desired-state changes and recovery; verify unchanged observation traffic does not churn authority publication.
- Use the configured heartbeat interval, outcome rate and reconnect distribution to calculate fleet request rates. For N hosts and interval H seconds, the settled heartbeat rate is N/H; bootstrap bursts and retry storms are additional traffic.
- Exercise controlled local/synthetic workloads first, then an authorized isolated load environment. Record p50/p95/p99 latency, errors, backlog, fixed-deadline misses and database/Worker saturation. Do not generate unsolicited traffic against live game servers.
- Check provider/source partition bounds, database writer serialization and operational limits against current official platform documentation, then define a measured sustainable fleet envelope and rollout limits.
- Preserve exact code/native versions and sanitized artifacts. Ordinary frame authentication remaining D1-free does not prove the real heartbeat mutation path is cheap enough for the target fleet.

This is an outstanding acceptance task, not a claim that the local fixture established production throughput or cost.
