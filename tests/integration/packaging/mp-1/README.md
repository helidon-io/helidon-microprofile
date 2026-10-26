# Packaging Integration Test MP1

This test exercises the following Helidon features using JAR and jlink packaging:

- MicroProfile CDI
- MicroProfile Server
- MicroProfile Access Log
- MicroProfile Fault Tolerance
- MicroProfile Metrics
- MicroProfile Health Check
- MicroProfile Rest Client
- MicroProfile Config
- YAML configuration
- JSON-P
- JSON-B
- MicroProfile JWT-Auth

To run this test:

```shell
mvn clean verify
mvn clean verify -Pjlink-image
```
