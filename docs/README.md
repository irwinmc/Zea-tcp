# Documentation

These guides describe Zea TCP Transport 0.8.3 and its supported application APIs.
Use Java 21 or later and the dependency version shown in the repository README.

## Application Integration

| Guide | Use it for |
| --- | --- |
| [Repository quick start](../README.md#quick-start) | Adding the dependency, creating an entry point and running examples |
| [Core components and security](CORE_COMPONENTS.md) | Supplying Game/services, authenticating players and configuring token keys |
| [Configuration and protocols](CONFIGURATION.md) | Loading properties, configuring listeners and understanding wire formats |
| [Event loops and lifecycle](EVENT_LOOP.md) | Preserving ordering, backpressure, payload ownership and shutdown behavior |
| [Slow business operations](SLOW_BUSINESS.md) | Offloading blocking work and applying its results in the correct order |
| [Monitoring](MONITORING.md) | Health endpoints, JSON/Prometheus metrics and application counters |
| [Banner customization](BANNER_CUSTOMIZATION.md) | Providing an application banner or overriding startup output |

## Release Changes

- [0.8.3](RELEASE_NOTES_0.8.3.md): bounded socket backpressure, unicast allocation
  reduction, streaming JSON and migration from 0.8.2.
- [0.8.2](RELEASE_NOTES_0.8.2.md): component injection, authentication defaults,
  resource budgets and migration from 0.8.1.
