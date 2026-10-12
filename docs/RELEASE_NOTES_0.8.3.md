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
   Validate resource budgets with the application's actual handlers and traffic.

See [configuration](CONFIGURATION.md), [event ownership](EVENT_LOOP.md),
[monitoring](MONITORING.md) and [slow business operations](SLOW_BUSINESS.md)
for the complete application contracts.

## Validation

The release checks include 250 regression cases and strict production and test
JavaDoc. Coverage includes ordering, backpressure, native-buffer ownership,
protocol boundaries and real TCP/WebSocket behavior. GitHub Actions validates
the release artifacts before publication.

Maven coordinates: `com.akakata:zea-tcp:0.8.3`.
