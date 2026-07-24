<!--@frontmatter
description: "Helidon MP Specifications"
navigation:
  icon: i-lucide-layers-3
-->
# Specifications

## MicroProfile

Helidon MP is an Eclipse MicroProfile 6.1 runtime. It implements and supports
the following specifications for standardized microservice APIs.

| Specification                                              | Version                           | Description                                                                                                     |
|------------------------------------------------------------|-----------------------------------|-----------------------------------------------------------------------------------------------------------------|
| [MicroProfile Config][mp-config]                           | [3.1][mp-config-spec]             | A flexible configuration framework with support for multiple sources and formats                                |
| [MicroProfile Fault Tolerance][mp-ft]                      | [4.0.2][mp-ft-spec]               | Common strategies for various system problems such as time-outs, retries, Circuit Breaker, etc.                 |
| [MicroProfile GraphQL][mp-graphql]                         | [2.0][mp-graphql-spec]            | API for working with GraphQL                                                                                    |
| [MicroProfile Health][mp-health]                           | [4.0.1][mp-health-spec]           | Health checks for automatic service restart/shutdown                                                            |
| [MicroProfile JWT Auth][mp-jwt]                            | [2.1][mp-jwt-spec]                | Defines a compact and self-contained way for securely transmitting information between parties as a JSON object |
| [MicroProfile Metrics][mp-metrics]                         | [5.1.2][mp-metrics-spec]          | Defining and exposing telemetry data in Prometheus and JSON formats                                             |
| [MicroProfile Open API][mp-openapi]                        | [3.1.2][mp-openapi-spec]          | Annotations for documenting your application endpoints                                                          |
| [MicroProfile Reactive Messaging][mp-reactive-messaging]   | [3.0.1][mp-reactive-messaging-spec] | Standard API for sending and receiving messages/events using streams                                          |
| [MicroProfile Reactive Streams Operators][mp-rs-operators] | [3.0.1][mp-rs-operators-spec]     | Control flow and error processing for event streams                                                             |
| [MicroProfile REST Client][mp-rest-client]                 | [3.0.1][mp-rest-client-spec]      | Type-safe API for RESTful Web Services                                                                          |
| [MicroProfile Telemetry][mp-telemetry]                     | [1.1][mp-telemetry-spec]          | Standard APIs and behavior for collecting and exporting telemetry data                                          |

## Jakarta EE

MicroProfile 6.1 includes the Jakarta EE Core Profile. Helidon MP implements
and supports the following Core Profile specifications.

| Specification                                      | Version                       | Description                                                 |
|----------------------------------------------------|-------------------------------|-------------------------------------------------------------|
| Jakarta Annotations                                | [2.1][annotations-spec]       | Common annotations used by Jakarta EE technologies          |
| Jakarta Context and Dependency Injection (CDI)     | [4.0][cdi-spec]               | Declarative dependency injection and supporting services    |
| Jakarta Dependency Injection                       | [2.0][inject-spec]            | Standard annotations for dependency injection               |
| Jakarta Interceptors                               | [2.1][interceptors-spec]      | Interceptors for method invocations and lifecycle events    |
| Jakarta JSON Processing (JSON-P)                   | [2.1][jsonp-spec]             | API to parse, generate, transform, and query JSON docs       |
| Jakarta JSON Binding (JSON-B)                      | [3.0][jsonb-spec]             | Binding framework for converting POJOs to/from JSON docs     |
| [Jakarta RESTful Web Services (JAX-RS)][mp-server] | [3.1][jaxrs-spec]             | API to develop web services following the REST pattern       |

Helidon MP also supports the following Jakarta specifications beyond the Core
Profile.

| Specification                         | Version                       | Description                                                 |
|---------------------------------------|-------------------------------|-------------------------------------------------------------|
| [Jakarta Bean Validation][validation] | [3.0][bv-spec]                | Object level constraint declaration and validation facility |
| [Jakarta WebSocket][jakarta-websocke] | [2.1][jakarta-websocket-spec] | API for Server and Client Endpoints for WebSocket protocol  |

[validation]: validation.md
[bv-spec]: https://jakarta.ee/specifications/bean-validation/3.0/jakarta-bean-validation-spec-3.0.html
[annotations-spec]: https://jakarta.ee/specifications/annotations/2.1/annotations-spec-2.1.html
[cdi-spec]: https://jakarta.ee/specifications/cdi/4.0/jakarta-cdi-spec-4.0.html
[inject-spec]: https://jakarta.ee/specifications/dependency-injection/2.0/jakarta-injection-spec-2.0.html
[interceptors-spec]: https://jakarta.ee/specifications/interceptors/2.1/interceptors-spec-2.1.html
[jsonp-spec]: https://jakarta.ee/specifications/jsonp/2.1/apidocs
[jsonb-spec]: https://jakarta.ee/specifications/jsonb/3.0/jakarta-jsonb-spec-3.0.html
[mp-server]: server.md
[jaxrs-spec]: https://jakarta.ee/specifications/restful-ws/3.1/jakarta-restful-ws-spec-3.1.html
[jakarta-websocke]: websocket.md
[jakarta-websocket-spec]: https://jakarta.ee/specifications/websocket/2.1/jakarta-websocket-spec-2.1.html
[mp-config]: config/config.md
[mp-config-spec]: https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html
[mp-ft]: fault-tolerance.md
[mp-ft-spec]: https://download.eclipse.org/microprofile/microprofile-fault-tolerance-4.0.2/microprofile-fault-tolerance-spec-4.0.2.html
[mp-graphql]: graphql.md
[mp-graphql-spec]: https://download.eclipse.org/microprofile/microprofile-graphql-2.0/microprofile-graphql-spec-2.0.html
[mp-health]: health.md
[mp-health-spec]: https://download.eclipse.org/microprofile/microprofile-health-4.0.1/microprofile-health-spec-4.0.1.html
[mp-jwt]: jwt.md
[mp-jwt-spec]: https://download.eclipse.org/microprofile/microprofile-jwt-auth-2.1/microprofile-jwt-auth-spec-2.1.html
[mp-metrics]: metrics/metrics.md
[mp-metrics-spec]: https://download.eclipse.org/microprofile/microprofile-metrics-5.1.2/microprofile-metrics-spec-5.1.2.html
[mp-openapi]: openapi/openapi.md
[mp-openapi-spec]: https://download.eclipse.org/microprofile/microprofile-open-api-3.1.2/microprofile-openapi-spec-3.1.2.html
[mp-reactive-messaging]: reactive-messaging/reactive-messaging.md
[mp-reactive-messaging-spec]: https://download.eclipse.org/microprofile/microprofile-reactive-messaging-3.0.1/microprofile-reactive-messaging-spec-3.0.1.html
[mp-rs-operators]: reactive-streams/rsoperators.md
[mp-rs-operators-spec]: https://download.eclipse.org/microprofile/microprofile-reactive-streams-operators-3.0.1/microprofile-reactive-streams-operators-spec-3.0.1.html
[mp-rest-client]: restclient/restclient.md
[mp-rest-client-spec]: https://download.eclipse.org/microprofile/microprofile-rest-client-3.0.1/microprofile-rest-client-spec-3.0.1.html
[mp-telemetry]: telemetry.md
[mp-telemetry-spec]: https://download.eclipse.org/microprofile/microprofile-telemetry-1.1/tracing/microprofile-telemetry-tracing-spec-1.1.pdf
