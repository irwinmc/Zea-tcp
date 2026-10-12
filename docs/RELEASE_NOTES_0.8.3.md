# Zea TCP Transport 0.8.3

Version 0.8.3 improves bounded socket backpressure, reduces unicast and JSON
encoding allocations, and preserves ordered Session and Game delivery.

## Changes

- Pause bounded socket drains when a channel becomes unwritable, flush admitted
  batches early, and resume from channel writability events. Bursts and slow
  readers retain FIFO ordering without treating a soft write watermark as a hard
  resource-budget failure.
- Keep one write deadline from admission through completion. Release pending
  payloads and reservations on completion, rejection, cancellation and closure,
  including stopped or rejected event-loop cleanup. Expose write-timeout counts
  through JSON and Prometheus flow-control metrics.
- Optimize explicit single-recipient sends by skipping identity deduplication and
  shared fan-out encoding leases. Preserve Game queue ordering, Session admission,
  overload handling and native payload ownership. Multiple-recipient sends retain
  canonical encoding reuse.
- Stream JSON directly into a bounded native application frame, avoiding a
  whole-payload heap array. Preserve the wire format, UTF-8 bytes, null controls
  and 65535-byte application-frame boundary. Release partial output on serializer
  failure or overflow, retaining the frame-limit exception.
- Add regressions for unicast ownership, mixed send ordering, burst and slow-reader
  backpressure, exact JSON bytes, protocol boundaries and failed serialization.
- Add isolated single-process benchmark helpers with resource guards, artifact
  checksums, separate client/server accounting, per-test reports and cleanup.
  Keep local SSH credentials and generated reports outside Git.
- Document CPU/memory budgets, measured latency targets and proposed external
  placement with sticky Game ownership. A cluster registry, gateway and live
  state migration are not implemented by this release.

## Migration from 0.8.2

1. Update the dependency to `com.akakata:zea-tcp:0.8.3`. Public event and sending
   APIs and network frame formats remain compatible. Java 21 remains required.
2. Retain the existing task, payload and encoded-byte limits. Soft channel
   unwritability now pauses admitted writes; hard budget rejection and the
   admitted-write deadline still protect resources. Review these budgets against
   representative traffic rather than increasing them to hide overload.
3. Continue treating published event data as immutable and following the
   documented native-buffer ownership contract. Unicast follows the normal
   Game-to-Session-to-socket retention path; fan-out keeps its shared encoding lease.
4. Keep authentication and required listener endpoint configuration from 0.8.2.
   Use controlled tests to establish deployment budgets. Proposed multi-process
   placement does not share in-memory Game state or recover a failed node.

See [configuration](CONFIGURATION.md), [event ownership](EVENT_LOOP.md),
[monitoring](MONITORING.md), [process budgets](MULTI_PROCESS_TUNING.md) and
[scale-out design](SCALE_OUT.md) for the complete contracts and limitations.

## Validation

The release checks include 250 regression cases, strict production and test
JavaDoc, and a signed Maven publication fixture using a local publisher server.
GitHub Actions repeats these checks before signing and publishing to Maven Central.

An implementation candidate was exercised in 43 isolated remote workloads,
including baseline comparisons and repeated capacity probes. They completed
606016 measured request/reply pairs with zero failed replies. Tests ran one
framework server at a time, with separate bounded clients and fractional CPU
quotas on an eight-vCPU, approximately-16-GiB host. Existing Java, MySQL and Redis
process identities were preserved, and task-owned remote resources were removed.

The longer-warmup 4 KiB JSON echo comparison reduced observed live-thread heap
allocation from about 18.0 KB to 8.4 KB per reply, approximately 53%. CPU and
throughput changes varied across rounds; no stable end-to-end speedup is claimed.
These synthetic, closed-loop measurements do not establish unrestricted process
limits, maximum online connections or production multi-process capacity.

Maven coordinates: `com.akakata:zea-tcp:0.8.3`.
