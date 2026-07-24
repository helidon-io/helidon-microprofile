<!--@frontmatter
description: "Helidon MP Observability"
navigation:
  icon: i-lucide-search
-->
# Observability

## Overview

Helidon MP integrates MicroProfile Health and MicroProfile Metrics with the
Helidon observability infrastructure. These MicroProfile features retain their
standard top-level endpoints, `/health` and `/metrics`.

Helidon also provides core observability features, such as configuration,
information, and logging observers. Those features are shared with Helidon SE
and use `/observe` as their default context root. See
[Helidon SE Observability][se-observability] for their dependencies, endpoints,
and configuration.

## Maven Coordinates

You do not need to add a separate observability dependency for MicroProfile
Health or MicroProfile Metrics. Their Helidon MP dependencies bring in the
required observability infrastructure.

For core observers which are not defined by MicroProfile, follow the
[Helidon SE Observability][se-observability] documentation.

## Feature Weight and Endpoint Conflicts

Helidon orders routing features by weight. Application endpoint routing has
weight 100 by default, while the observability feature has default weight 80.
As a result, an application resource such as `/{name}` can receive requests for
`/metrics` and `/health` before the observability feature does.

To prioritize observability endpoints, assign the observe feature a weight from
101 to 200 in `META-INF/microprofile-config.properties`:

```properties [microprofile-config.properties]
server.features.observe.weight = 120
```

Helidon does not enforce this range, but values from 101 to 200 preserve the
expected ordering relative to other features such as security and CORS.

## Endpoints

- MicroProfile Health uses `/health` by default. See
  [MicroProfile Health](health.md).
- MicroProfile Metrics uses `/metrics` by default. See
  [MicroProfile Metrics](metrics/metrics.md).
- Core Helidon observers use subpaths of `/observe` by default. See
  [Helidon SE Observability][se-observability].

## Configuration

Configure the MicroProfile endpoints using their Helidon MP documentation:

- [Metrics configuration](metrics/metrics.md#configuration-options)
- [Health configuration](health.md#configuration)

For core observer configuration, see [Helidon SE Observability][se-observability]
and the external [Observe feature configuration reference][observe-config].

## Reference

- [MicroProfile Metrics Specification][microprofile-metrics]
- [MicroProfile Metrics](metrics/metrics.md)
- [MicroProfile Health](health.md)
- [Helidon SE Observability][se-observability]

[microprofile-metrics]: https://download.eclipse.org/microprofile/microprofile-metrics-5.1.2/microprofile-metrics-spec-5.1.2.html
[observe-config]: https://helidon.io/docs/latest/config/io.helidon.webserver.observe.ObserveFeature#configuration-options
[se-observability]: https://helidon.io/docs/latest/se/observability
