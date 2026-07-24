<!--@frontmatter
description: "Reactive Messaging support for Oracle AQ"
-->
# Oracle AQ Connector

## Overview

Connecting streams to Oracle AQ with Reactive Messaging couldn’t be easier. This
connector extends Helidon’s JMS connector with Oracle’s AQ-specific API.

## Maven Coordinates

To enable AQ Connector, add the following dependency to your project’s `pom.xml`
(see [Managing Dependencies](../dependency-management.md)).

```xml [pom.xml]
<dependency>
  <groupId>io.helidon.microprofile.messaging.connectors.aq</groupId>
  <artifactId>helidon-microprofile-messaging-connectors-aq</artifactId>
</dependency>
```

## Configuration

Connector name: `helidon-aq`

### Configured JMS Factory

The simplest possible usage is leaving construction of `AQjmsConnectionFactory`
to the connector.

Example of connector config:

```yaml
mp:
  messaging:

    connector:
      helidon-aq:
        transacted: false
        acknowledge-mode: CLIENT_ACKNOWLEDGE
        url: jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(Host=192.168.0.123)(Port=1521))(CONNECT_DATA=(SID=TESTSID)))
        user: gandalf
        password: mellon

    outgoing.to-aq:
      connector: helidon-aq
      destination: TESTQUEUE
      type: queue

    incoming.from-aq:
      connector: helidon-aq
      destination: TESTQUEUE
      type: queue
```

### Injected JMS factory

If you need more advanced configurations, connector can work with injected
`AQjmsConnectionFactory`:

```java [Inject]
@Produces
@ApplicationScoped
@Named("aq-orderdb-factory")
public AQjmsConnectionFactory connectionFactory() throws JMSException {
    AQjmsQueueConnectionFactory fact = new AQjmsQueueConnectionFactory();
    fact.setJdbcURL(config.get("jdbc.url").asString().get());
    fact.setUsername(config.get("jdbc.user").asString().get());
    fact.setPassword(config.get("jdbc.pass").asString().get());
    return fact;
}
```

```yaml [Config]
jdbc:
  url: jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(Host=192.168.0.123)(Port=1521))(CONNECT_DATA=(SID=TESTSID)))
  user: gandalf
  pass: mellon

mp:
  messaging:
    connector:
      helidon-aq:
        named-factory: aq-orderdb-factory

    outgoing.to-aq:
      connector: helidon-aq
      session-group-id: order-connection-1
      destination: TESTQUEUE
      type: queue

    incoming.from-aq:
      connector: helidon-aq
      session-group-id: order-connection-1
      destination: TESTQUEUE
      type: queue
```

## Usage

### Consuming

Consuming one by one unwrapped value:

```java
@Incoming("from-aq")
public void consumeAq(String msg) {
    System.out.println("Oracle AQ says: " + msg);
}
```

Consuming one by one, manual ack:

```java
@Incoming("from-aq")
@Acknowledgment(Acknowledgment.Strategy.MANUAL)
public CompletionStage<Void> consumeAq(AqMessage<String> msg) {
    // direct commit
    //msg.getDbConnection().commit();
    System.out.println("Oracle AQ says: " + msg.getPayload());
    // ack commits only in non-transacted mode
    return msg.ack();
}
```

### Producing

Producing to AQ:

```java
@Outgoing("to-aq")
public PublisherBuilder<String> produceToAq() {
    return ReactiveStreams.of("test1", "test2");
}
```
