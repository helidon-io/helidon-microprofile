# MicroProfile metrics endpoint benchmarks

The opt-in `jmh` profile compiles the benchmark as test code. It does not add
benchmark classes or JMH dependencies to the production artifact.

`MpMetricsEndpointBenchmark` exercises the real MP metrics routes and response
serialization through Helidon's `DirectClient`. It excludes network transport,
CDI startup, and metric registration from the measured operations. Trial setup
creates the same counter names in application, base, and vendor scopes, removes
initial system meters, and verifies response status, selection, and values.

The four workloads cover unfiltered and name/scope-selected Prometheus and JSON
GET requests. The default data sizes are 100 and 1000 counter names per scope.
Metadata OPTIONS scanning is not measured by this benchmark.

Use the repository's required JDK and run from the repository root:

```shell
mvn -pl :helidon-microprofile-metrics -Pjmh test-compile
mvn -pl :helidon-microprofile-metrics -Pjmh exec:exec -Dexec.executable=java -Dexec.classpathScope=test '-Dexec.args=--enable-native-access=ALL-UNNAMED -cp %classpath org.openjdk.jmh.Main ^io[.]helidon[.]microprofile[.]metrics[.]MpMetricsEndpointBenchmark[.].*$ -prof gc -rf json -rff target/metrics-jmh.json -to 30s -foe true -jvmArgsAppend --enable-native-access=ALL-UNNAMED'
```

The default measurement uses one thread, two forks, three one-second warmup
iterations, and five one-second measurement iterations. Select one method or
override the parameters for a shorter wiring smoke test; smoke results are not
performance evidence. For comparisons, use identical benchmark sources, JDK,
dependencies, parameters, and host conditions for both revisions. Compare time
and allocation results, retain the reported uncertainty, and distinguish direct
route costs from networked application throughput.
