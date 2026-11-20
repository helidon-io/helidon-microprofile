<!--@frontmatter
description: "MicroProfile Telemetry"
navigation:
  icon: i-lucide-chart-line
-->
# Telemetry

Helidon implements MicroProfile Telemetry 2.2,
supporting tracing, metrics, and log export. It creates and propagates traces for
Jakarta RESTful Web Services and MicroProfile REST Client requests, and it provides
the OpenTelemetry APIs for manual instrumentation. It also measures incoming REST
request durations, collects JVM metrics when runtime telemetry is enabled, and
exports Java Util Logging (JUL) records with the current trace context.

## Maven Coordinates

Add the following dependency to your project’s `pom.xml` (see
[Managing Dependencies](dependency-management.md)).

```xml [pom.xml]
<dependency>
  <groupId>io.helidon.microprofile.telemetry</groupId>
  <artifactId>helidon-microprofile-telemetry</artifactId>
</dependency>
```

The telemetry module includes the OpenTelemetry OTLP exporter for traces,
metrics, and logs.

## Usage

OpenTelemetry connects spans across services using propagated context. Helidon
provides automatic REST instrumentation and injectable APIs for application
instrumentation. Exporters send telemetry to a collector or backend.

Helidon supports both automatic and manual instrumentation:

- Automatic instrumentation creates spans for incoming Jakarta REST requests,
  outgoing Jakarta REST client requests, and MicroProfile REST Client requests.
  It also records `http.server.request.duration` for incoming REST requests through
  response completion and collects JVM metrics when runtime telemetry is enabled.
- Logging integration exports JUL records, including `System.Logger` records
  routed through JUL, preserving severity, exceptions, and trace correlation.
- Manual instrumentation uses OpenTelemetry annotations and APIs from your
  application code.

### Using `@WithSpan`

Add `@WithSpan` to a method on a CDI bean to create a span when the method is
invoked. Use `@SpanAttribute` on method parameters to add attributes to that
span. Spans include `code.function.name` and honor the annotation's span kind
and context inheritance.

<!--@mdc ::code-callout -->
```java
@ApplicationScoped
class GreetingService {

    @WithSpan // <1>
    void refreshGreeting() {
        // Update the greeting.
    }

    @WithSpan("personalized-greeting") // <2>
    String greeting(@SpanAttribute("name") String name) { // <3>
        return "Hello " + name;
    }
}
```
1. Create a span using the default span name.
2. Create a span with an explicit name.
3. Add the method argument to the span as an attribute.
<!--@mdc :: -->

Spans start at method entry and end at synchronous method completion,
`CompletionStage` completion, or, for asynchronous REST resource methods,
completion or failure of response writing. They are active during method execution and,
for asynchronous REST responses, resumed response processing; application tasks
on other threads must propagate the span context explicitly.

### Working With Tracers

Helidon makes `io.opentelemetry.api.OpenTelemetry` and
`io.opentelemetry.api.trace.Tracer` available for CDI injection. Use a `Tracer`
to create spans manually.

<!--@mdc ::code-callout -->
```java
@Path("/tasks")
public class TaskResource {

    @Inject
    Tracer tracer; // <1>

    @POST
    public Response createTask() {
        Span span = tracer.spanBuilder("create-task") // <2>
                .setAttribute("task.type", "standard")
                .startSpan();

        try (Scope ignored = span.makeCurrent()) { // <3>
            // Create the task.
            return Response.accepted().build();
        } finally {
            span.end(); // <4>
        }
    }
}
```
1. Inject the OpenTelemetry `Tracer`.
2. Build and start a span.
3. Make the span current while the operation runs.
4. End the span in a `finally` block.
<!--@mdc :: -->

### Working With the Current Span and Baggage

Inject the current OpenTelemetry `Span` and `Baggage`, or access them using
`Span.current()` and `Baggage.current()`. Baggage carries application-defined
key/value pairs through the current context.

```java
@Inject
Span span;

@Inject
Baggage baggage;
```

Use `span.setAttribute(...)` to annotate the current operation and
`baggage.getEntryValue(...)` to read an entry. See the
[OpenTelemetry API documentation][opentelemetry-manual] for further examples.

### Responding to Span Lifecycle Events

Applications and libraries can register Helidon span listeners to receive
notifications at several points in a span’s lifecycle:

- Before a span starts
- After a span starts
- After a span ends
- After a span is activated
- After the activated scope closes

See the [Helidon SE span lifecycle documentation][helidon-se-docum] for details.
In a Helidon MP application, add [`@CallbackEnabled`][callbackenabled] to an
injected OpenTelemetry `Tracer` or `Span` to receive those callbacks for spans
created through that injected object.

```java
@Inject
@CallbackEnabled
Tracer tracer;
```

The callback-enabled object implements the corresponding OpenTelemetry
interface, but it is a Helidon wrapper which invokes the registered listeners.

### Controlling Automatic Span Creation

By default, Helidon MP Telemetry creates a span for each incoming Jakarta REST
request and outgoing Jakarta REST client request. Applications can selectively
suppress these automatic spans.

#### Incoming REST Requests

Implement the
[`HelidonTelemetryContainerFilterHelper` interface][helidontelemetry] to decide
whether Helidon should create a span for an incoming request. Helidon calls all
discovered helpers with the Jakarta REST
[`ContainerRequestContext`][jakarta-rest-con]. If any helper returns `false`,
Helidon suppresses the automatic span.

The helper must have a CDI bean-defining annotation, such as
`@ApplicationScoped`.

```java
@ApplicationScoped
public class CustomRestRequestFilterHelper implements HelidonTelemetryContainerFilterHelper {

    @Override
    public boolean shouldStartSpan(ContainerRequestContext requestContext) {
        return requestContext.getUriInfo().getPath().endsWith("greet");
    }
}
```

#### Outgoing REST Client Requests

Implement the
[`HelidonTelemetryClientFilterHelper` interface][helidontelemetry-client] to
decide whether Helidon should create a span for an outgoing request. Helidon
calls all discovered helpers with the Jakarta REST
[`ClientRequestContext`][jakarta-rest-client]. If any helper returns `false`,
Helidon suppresses the automatic span.

The helper must have a CDI bean-defining annotation, such as
`@ApplicationScoped`.

```java
@ApplicationScoped
public class CustomRestClientRequestFilterHelper implements HelidonTelemetryClientFilterHelper {

    @Override
    public boolean shouldStartSpan(ClientRequestContext requestContext) {
        return requestContext.getUri().getPath().endsWith("greet");
    }
}
```

## Configuration

> [!IMPORTANT]
> MicroProfile Telemetry is disabled by default. Set
> `otel.sdk.disabled=false` in a MicroProfile Config source to enable it.

Helidon passes `otel.*` MicroProfile Config properties to the OpenTelemetry SDK
autoconfiguration support, including ServiceLoader exporters, propagators,
samplers, resources, and customizers. When enabled, all three signals default
to OTLP export. To disable an individual exporter, set `otel.traces.exporter`,
`otel.metrics.exporter`, or `otel.logs.exporter` to `none`.

Setting `OTEL_SDK_DISABLED=false` in the environment or `otel.sdk.disabled=false`
as a Java system property enables runtime telemetry. Runtime configuration then
controls the SDK used by both the runtime and the application; application
configuration does not reconfigure that SDK. Setting `otel.sdk.disabled=false`
only in application configuration enables application telemetry without enabling
runtime JVM metrics. Configuration is read at initialization and requires a
restart to change.

Helidon uses `mp.application.name` as the fallback `service.name` for
application-only telemetry, unless configuration or a resource provider supplies
one. It also accepts `otel.exporter.name` as a compatibility fallback for
`mp.telemetry.tracing.name`.

The following configuration enables telemetry and selects OTLP over HTTP/protobuf:

```properties [microprofile-config.properties]
otel.sdk.disabled=false
otel.service.name=greeting-service
otel.exporter.otlp.protocol=http/protobuf
```

This exports enabled signals to a collector listening on port 4318. Use the
per-signal exporter settings above when the backend accepts only selected signals.

### Automatic REST Spans

Automatic incoming REST span names use the HTTP method followed by the matched
route, such as `GET /greet/{name}`. Using the route template keeps span names
independent of individual path parameter values.

Outgoing client span names use the HTTP method, such as `GET`. For unrecognized
methods, span names use `HTTP` and `http.request.method` is `_OTHER`.

Helidon ends the incoming REST span after response serialization and encoding,
when the last byte has been buffered for writing to the socket. This includes
server-side work preparing the response; it does not measure network delivery
or wait for the client to receive or acknowledge the response.

### OpenTelemetry Java Agent

If the application runs with the OpenTelemetry Java agent, set the following
property so Helidon explicitly reuses the OpenTelemetry instance configured by
the agent:

```properties [microprofile-config.properties]
otel.agent.present=true
```

## OTLP Example

A complete application demonstrating method spans, custom spans, and propagation
between REST services is available in the [Helidon examples
repository][helidon-example]. Use the dependency and configuration shown above.

For a tracing backend, Jaeger can receive OTLP over HTTP on port 4318 and display
traces in its UI on port 16686. See its [getting started
documentation][jaeger-getting-started] for setup instructions. Configure
`otel.metrics.exporter=none` and `otel.logs.exporter=none` for tracing-only export.

## Reference

- [MicroProfile Telemetry 2.2 specification][microprofile-telemetry]
- [OpenTelemetry documentation](https://opentelemetry.io/docs/)
- [OpenTelemetry Java manual instrumentation][opentelemetry-manual]

[helidon-se-docum]: https://helidon.io/docs/latest/se/tracing#span-lifecycle
[callbackenabled]: https://helidon.io/docs/latest/apidocs/io.helidon.microprofile.telemetry/io/helidon/microprofile/telemetry/CallbackEnabled.html
[helidontelemetry]: https://helidon.io/docs/latest/apidocs/io.helidon.microprofile.telemetry/io/helidon/microprofile/telemetry/spi/HelidonTelemetryContainerFilterHelper.html
[jakarta-rest-con]: https://jakarta.ee/specifications/restful-ws/3.1/apidocs/jakarta.ws.rs/jakarta/ws/rs/container/containerrequestcontext
[helidontelemetry-client]: https://helidon.io/docs/latest/apidocs/io.helidon.microprofile.telemetry/io/helidon/microprofile/telemetry/spi/HelidonTelemetryClientFilterHelper.html
[jakarta-rest-client]: https://jakarta.ee/specifications/restful-ws/3.1/apidocs/jakarta.ws.rs/jakarta/ws/rs/client/clientrequestcontext
[opentelemetry-manual]: https://opentelemetry.io/docs/languages/java/api/
[jaeger-getting-started]: https://www.jaegertracing.io/docs/latest/getting-started/
[helidon-example]: https://github.com/helidon-io/helidon-examples/tree/dev-27.x/examples/microprofile/telemetry
[microprofile-telemetry]: https://download.eclipse.org/microprofile/microprofile-telemetry-2.2/microprofile-telemetry-spec-2.2.html
