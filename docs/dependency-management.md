<!--@frontmatter
description: "Manage Helidon dependencies with the BOM"
navigation:
  icon: i-lucide-package
-->
# Dependency Management

Helidon MicroProfile provides a "Bill Of Materials" (BOM) to manage
dependencies. This is a special Maven POM file that provides dependency
management.

Using the Helidon MicroProfile BOM allows you to use Helidon MicroProfile
component dependencies with a single version.

## Application POMs

If you created your application using the [Helidon Project
Starter](https://helidon.io/starter) or [Helidon
CLI](https://helidon.io/docs/latest/cli), then your project will have a Helidon
Application POM as its parent POM. In this case, you get Helidon dependency
management automatically.

If your project does not use a Helidon Application POM as its parent, then you
must import the Helidon BOM POM.

## BOM POM

To import the Helidon BOM POM, add the following snippet to your `pom.xml` file.

Import the Helidon BOM:

```xml [pom.xml]
<dependencyManagement>
  <dependencies>
    <dependency>
        <groupId>io.helidon.microprofile</groupId>
        <artifactId>helidon-microprofile-bom</artifactId>
        <version>${helidon.mp.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Helidon Components

After you import the BOM, you can declare dependencies on Helidon components
without specifying a version.

Component dependency:

```xml [pom.xml]
<dependency>
  <groupId>io.helidon.microprofile.config</groupId>
  <artifactId>helidon-microprofile-config-yaml</artifactId>
</dependency>
```
