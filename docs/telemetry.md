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

To enable MicroProfile Telemetry, either add a dependency on the
[Helidon MicroProfile bundle](introduction.md) or add the following dependency
to your project’s `pom.xml` (see [Managing
Dependencies](dependency-management.md)).

```xml [pom.xml]
<dependency>
  <groupId>io.helidon.microprofile.telemetry</groupId>
  <artifactId>helidon-microprofile-telemetry</artifactId>
</dependency>
```

The telemetry module includes the OpenTelemetry OTLP exporter for traces,
metrics, and logs.

## Compatibility

Helidon uses the core-managed OpenTelemetry 1.67.0. MicroProfile Telemetry 2.2
specifies a 1.66.x baseline; this remains a documented version exception.

Automatic incoming REST span names always include the HTTP method and matched
route, for example `GET /greet/{name}`. The previously deprecated compatibility
settings have been removed in this release:

| Removed property | Behavior in this release |
|------------------|--------------------------|
| `telemetry.span.name-includes-method` | Span names always include the HTTP method. |
| `telemetry.span.full.url` | Span names always use the matched route rather than the full URL. |
| `telemetry.span.includes-response-write` | Spans always include response serialization and writing. |

Remove these properties from your configuration; they no longer affect telemetry.
Update dashboards, alerts, and queries that depend on the older span names or
exclude response-writing time. See [Automatic REST spans](#automatic-rest-spans)
for the response completion boundary.

The stable HTTP semantic conventions also change exported attribute names:

| Earlier attribute | Telemetry 2.2 attribute |
|-------------------|-------------------------|
| `http.method` | `http.request.method` |
| `http.status_code` | `http.response.status_code` |
| `http.scheme` | `url.scheme` |
| `http.target` | `url.path` and `url.query` |
| `http.url` | `url.full` |

Update collector rules and queries that use the earlier names. `@WithSpan` spans
include `code.function.name` and honor the annotation's span kind and context inheritance.
`mp.telemetry.tracing.name` selects the tracing instrumentation scope name;
`otel.exporter.name` remains a Helidon compatibility fallback.

## Usage

A distributed trace represents the path of one request through an application
or several services. Each trace contains one or more spans. A span describes an
operation and records its name, timing, status, and attributes. OpenTelemetry
context propagation connects spans created by different services into the same
trace.

An exporter sends completed spans to a collector or backend. Helidon uses
OpenTelemetry autoconfiguration for the exporter; the examples on this page use
OTLP.

![General understanding of OpenTelemetry tracing](images/telemetry/telemetry-general.png)

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
span.

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

For a method returning `CompletionStage` (including `CompletableFuture`), the
span remains open until the stage completes. Exceptional completion and
cancellation mark the span as failed. The original stage is returned unchanged.

For an asynchronous Jakarta REST resource method, the span instead remains open
until the response has finished writing or a terminal response failure occurs.
This applies both to resource methods returning `CompletionStage` and to methods
using `@Suspended AsyncResponse`. The method span is current during response
serialization, so spans created by response writers are its children. This is
Helidon behavior beyond the explicit requirements of MicroProfile Telemetry
2.2. Response completion uses the server's send boundary; it does not imply
that the client has acknowledged receipt.

An open span is not automatically current on every application thread. The
interceptor restores the calling thread's context when the method returns.
Application-created asynchronous tasks must propagate context using an
OpenTelemetry context-aware executor or `Context.current().wrap(task)`, capturing
the context inside the annotated method before submitting the task.

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
                .setSpanKind(SpanKind.INTERNAL)
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

### Working With the Current Span

Inject the current OpenTelemetry `Span`, or obtain it using `Span.current()`.

<!--@mdc ::code-callout -->
```java
@Path("/tasks")
public class TaskResource {

    @Inject
    Span span; // <1>

    @GET
    @Path("/current")
    public Response currentSpan() {
        span.setAttribute("lookup.source", "injected"); // <2>
        return Response.ok().build();
    }

    @GET
    @Path("/current/static")
    public Response currentSpanStatic() {
        Span.current().setAttribute("lookup.source", "static"); // <3>
        return Response.ok().build();
    }
}
```
1. Inject the current span.
2. Use the injected span.
3. Use `Span.current()` to access the current span.
<!--@mdc :: -->

### Working With Baggage

OpenTelemetry baggage carries application-defined key/value pairs through the
current context. Inject the current `Baggage`, or obtain it using
`Baggage.current()`.

<!--@mdc ::code-callout -->
```java
@Path("/tasks")
public class TaskResource {

    @Inject
    Baggage baggage; // <1>

    @GET
    @Path("/tenant")
    public Response tenant() {
        return Response.ok(baggage.getEntryValue("tenant.id")).build(); // <2>
    }

    @GET
    @Path("/tenant/static")
    public Response tenantStatic() {
        return Response.ok(Baggage.current().getEntryValue("tenant.id")).build(); // <3>
    }
}
```
1. Inject the current baggage.
2. Read an entry from the injected baggage.
3. Use `Baggage.current()` to access the current baggage.
<!--@mdc :: -->

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

For application-only telemetry, `mp.application.name` supplies a default
`service.name`. Explicit `otel.service.name`, `service.name` in
`otel.resource.attributes`, and resource providers take precedence over that
fallback. The application name does not name a shared runtime SDK.

OpenTelemetry resource providers detect host, operating system, process, and JVM
metadata, plus container and JAR/manifest metadata when available. Configure
`otel.java.enabled.resource.providers` or `otel.java.disabled.resource.providers`
with provider class names to control discovery, and `otel.resource.disabled.keys`
to remove individual attributes. Explicit `otel.resource.attributes` override
detected values. The SDK supplies `service.instance.id` unless another provider
or explicit configuration supplies it.

Inject `io.opentelemetry.api.metrics.Meter` to create application instruments.
HTTP requests record `http.server.request.duration` in seconds. Runtime telemetry
also registers the required OpenTelemetry JVM memory, GC, thread, class, and CPU
instruments. Telemetry does not require MicroProfile Metrics.

JUL records are bridged to OpenTelemetry logs with severity, exception details,
and current trace context. The existing JUL backend also handles `System.Logger`.
OpenTelemetry exporter diagnostics are not forwarded into the log pipeline.

The following configuration enables tracing and exports spans using OTLP over
HTTP/protobuf:

```properties [microprofile-config.properties]
otel.sdk.disabled=false
otel.service.name=greeting-service
otel.exporter.otlp.protocol=http/protobuf
```

This configuration sets the service name recorded with exported spans, selects
the OTLP trace exporter, uses the OTLP HTTP/protobuf transport, and sends data
to a collector or backend listening on port 4318.

You can configure batching, sampling, resource attributes, headers, TLS, and
other exporter settings using the standard [OpenTelemetry Java SDK
configuration][opentelemetry-config].

### Automatic REST Spans

Automatic incoming REST span names use the HTTP method followed by the matched
route, such as `GET /greet/{name}`. Using the route template keeps span names
independent of individual path parameter values.

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

The following example exports traces from a Helidon MP application to an
OTLP-compatible backend. Jaeger is one possible backend: configure Jaeger to
receive OTLP over HTTP on port 4318 and open its UI on port 16686. See the
[Jaeger getting started documentation][jaeger-getting-started] for current
setup instructions.

Helidon exports OTLP directly to the backend’s OTLP endpoint; no Jaeger-specific
exporter is involved.

Add both the Helidon MP Telemetry dependency and the OpenTelemetry OTLP exporter
dependency shown in [Maven Coordinates](#maven-coordinates). Then add the OTLP
configuration shown in [Configuration](#configuration).

### Tracing at Method Level

Use `@WithSpan` to add an application span around a method.

<!--@mdc ::code-callout -->
```java
@Path("/greet")
public class GreetResource {

    @GET
    @WithSpan("default") // <1>
    public String getDefaultMessage() {
        return "Hello World";
    }
}
```
1. Create an application span named `default`.
<!--@mdc :: -->

Call the endpoint:

```shell [Terminal]
curl localhost:8080/greet
Hello World
```

If Jaeger is receiving the OTLP data, select `greeting-service` in the Jaeger
UI to inspect the trace.

![Greeting service tracing output](images/telemetry/telemetry-greeting-jaeger.png)

### Adding a Custom Span

Inject an OpenTelemetry `Tracer` to add a span manually.

<!--@mdc ::code-callout -->
```java
@Inject
Tracer tracer; // <1>

@GET
@Path("custom")
@Produces(MediaType.APPLICATION_JSON)
@WithSpan
public JsonObject useCustomSpan() {
    Span span = tracer.spanBuilder("custom") // <2>
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("attribute", "value")
            .startSpan();
    try (Scope ignored = span.makeCurrent()) {
        return Json.createObjectBuilder()
                .add("Custom Span", span.toString())
                .build();
    } finally {
        span.end(); // <3>
    }
}
```
1. Inject the OpenTelemetry `Tracer`.
2. Create and start a custom span.
3. End the span after the operation completes.
<!--@mdc :: -->

![Custom span usage](images/telemetry/telemetry-custom-jaeger.png)

### Propagating a Trace Between Services

Automatic client instrumentation propagates the OpenTelemetry context when one
service invokes another using a Jakarta REST client or MicroProfile REST Client.
The receiving service creates its server span as a child in the same trace.

```java
@Uri("http://localhost:8081/secondary")
WebTarget target;

@GET
@Path("/outbound")
@WithSpan("outbound")
public String outbound() {
    return target.request()
            .accept(MediaType.TEXT_PLAIN)
            .get(String.class);
}
```

The secondary service can add its own application span:

```java
@GET
@WithSpan
public String getSecondaryMessage() {
    return "Secondary";
}
```

The result is a single distributed trace containing the client, server, and
application spans.

![Secondary service outbound call](images/telemetry/telemetry-outbound-jaeger.png)

The complete example is available in the [Helidon examples
repository][helidon-example].

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
[opentelemetry-config]: https://opentelemetry.io/docs/languages/java/configuration/
[opentelemetry-manual]: https://opentelemetry.io/docs/languages/java/api/
[jaeger-getting-started]: https://www.jaegertracing.io/docs/latest/getting-started/
[helidon-example]: https://github.com/helidon-io/helidon-examples/tree/dev-27.x/examples/microprofile/telemetry
[microprofile-telemetry]: https://download.eclipse.org/microprofile/microprofile-telemetry-2.2/microprofile-telemetry-spec-2.2.html
