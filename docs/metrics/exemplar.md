<!--@frontmatter
description: "Helidon Exemplar Support"
-->
# OpenMetrics Exemplar

## Overview

A meter typically reflects the usage of a *single* point in your service which
processes *multiple* requests over time. A value such as the total time consumed
by a given REST endpoint which can be invoked multiple times underscores the
aggregate nature of meter values; Helidon accumulates the time from all requests
in the total duration.

Tracing, on the other hand, captures the usage of *multiple* parts of your code
as your service responds to a *single* request.

Metrics and tracing come together in Helidon’s support for exemplars.

> [!NOTE]
> [*exemplar*][exemplar] - one that serves as a model or example
> <br/>
> —Merriam-Webster Dictionary

In the context of metrics, an *exemplar* for a given meter is a specific sample
which, in some sense, made a typical contribution to the meter’s value. For
example, an exemplar for a `Counter` might be the most recent sample which
updated the counter. The metrics output identifies the exemplar sample using the
span and trace IDs of the span and trace which triggered that sample.

Exemplar support in Helidon relies on the exemplar support provided by the
underlying metrics implementation. Helidon’s metrics provider records exemplars
using its Prometheus meter registry and exposes them in OpenMetrics output
(media type `application/openmetrics-text`).

## Maven Coordinates

To enable OpenMetrics exemplar support, add the following dependency to your
project’s `pom.xml` (see [Managing
Dependencies](../dependency-management.md)).

```xml [pom.xml]
<dependency>
  <groupId>io.helidon.metrics</groupId>
  <artifactId>helidon-metrics-trace-exemplar</artifactId>
  <scope>runtime</scope>
</dependency>
```

Exemplars use the current OpenTelemetry span and trace identifiers. Enable
[MicroProfile Telemetry](../telemetry.md) in the application, either through the
Helidon MicroProfile bundle or with the following dependency:

```xml [pom.xml]
<dependency>
  <groupId>io.helidon.microprofile.telemetry</groupId>
  <artifactId>helidon-microprofile-telemetry</artifactId>
</dependency>
```

If you want to inspect the corresponding traces in an observability backend,
also add the OpenTelemetry OTLP exporter:

```xml [pom.xml]
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

## Usage

Enable MicroProfile Telemetry in `META-INF/microprofile-config.properties`:

```properties [microprofile-config.properties]
otel.sdk.disabled=false
```

Configure the OTLP endpoint as described in [MicroProfile
Telemetry](../telemetry.md#configuration) if you want to export and inspect the
traces. Exemplar support itself needs no additional configuration. It records
the current OpenTelemetry span and trace identifiers whenever a supported meter
is updated while a span is active. The active span can come from Helidon’s
automatic REST instrumentation, `@WithSpan`, or manual OpenTelemetry
instrumentation.

### Interpreting Exemplars

Each exemplar reflects a sample described by a label, a value, and a timestamp.
When a client accesses the `/metrics` endpoint and specifies that it accepts the
`application/openmetrics-text` media type, the label, value, and timestamp
appear in the OpenMetrics response for meters that support exemplars.

The exemplar information in the output describes a single, actual sample that
is representative of the statistical value as recorded by the underlying
Prometheus meter registry.

### Output Format

In the OpenMetrics output, an exemplar actually appears as a comment appended to
the normal OpenMetrics output.

*OpenMetrics format with exemplars*

meter-identifier meter-value # exemplar-label sample-timestamp

Even downstream consumers of OpenMetrics output that do not recognize the
exemplar format should continue to work correctly (as long as they *do*
recognize comments).

Some metrics backends and their UIs understand the exemplar format, allowing
you to browse meters and then navigate directly to the trace for the meter’s
exemplar.

## Examples

Once you enable exemplar support you can see the exemplars in the metrics
output.

```log [Output]
# TYPE counterForPersonalizedGreetings counter
# HELP counterForPersonalizedGreetings
counterForPersonalizedGreetings_total{scope="application"} 4.0 # {span_id="00f067aa0ba902b7",trace_id="4bf92f3577b34da6a3ce929d0e0e4736"} 1.0 1696889651.779
```

The exemplar (the portion following the `#`) is a sample corresponding to an
update to the counter, showing the span and trace identifiers, the amount by
which the counter was updated (`1.0`), and the timestamp recording when the
update occurred expressed as seconds in the UNIX epoch (`1696889651.779`).

## Additional Information

Brief discussion of [exemplars in the OpenMetrics spec][exemplars-in-the]

[exemplar]: https://www.merriam-webster.com/dictionary/exemplar
[exemplars-in-the]: https://github.com/OpenObservability/OpenMetrics/blob/main/specification/OpenMetrics.md#exemplars
