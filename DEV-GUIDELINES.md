# Helidon MicroProfile Development Guidelines

These guidelines extend the
[Helidon core development guidelines](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md).

MicroProfile-specific rule identifiers use the `MP-` namespace. They follow the core identifier-preservation and tombstone
policy in [Core Rule 0.2](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-0-2) and
[Core Rule 0.3](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-0-3).

<a id="chapter-mp-0"></a>
## MP-0. Overlay governance

<a id="rule-mp-0-1"></a>**Rule MP-0.1 — Apply the core guidelines by default.** Follow every core rule unless this
document explicitly overrides it for Helidon MicroProfile.

<a id="chapter-mp-1"></a>
## MP-1. External contracts

<a id="rule-mp-1-1"></a>**Rule MP-1.1 — Follow external contracts.** Where a MicroProfile, Jakarta, or integrated
framework contract conflicts with the core or MicroProfile guidelines, follow the external contract.

<a id="rule-mp-1-2"></a>**Rule MP-1.2 — Limit external-contract divergence.** Keep the divergence limited to the
integration boundary governed by that contract.

<a id="chapter-mp-2"></a>
## MP-2. Configuration and programmatic API

<a id="rule-mp-2-1"></a>**Rule MP-2.1 — Exempt CDI extension configuration.** CDI components configured from a CDI
extension are exempt from
[Core Rule 5.1](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-5-1).

<a id="chapter-mp-3"></a>
## MP-3. Testing

<a id="rule-mp-3-1"></a>**Rule MP-3.1 — Follow required test frameworks.** TCKs, framework-integration tests, and modules
that provide testing-framework support may use the framework required by their contract instead of
[Core Rule 10.4](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-10-4).

<a id="chapter-mp-4"></a>
## MP-4. Maven and modules

<a id="rule-mp-4-1"></a>**Rule MP-4.1 — Use the MicroProfile group hierarchy.** This rule overrides
[Core Rule 4.2.2.1](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-4-2-2-1): Maven group IDs use
the `io.helidon.microprofile` hierarchy. Modules under `microprofile/jersey/` retain their existing
`io.helidon.jersey*` group IDs for backward compatibility.

<a id="rule-mp-4-2"></a>**Rule MP-4.2 — Use the MicroProfile bundle location.** This rule overrides
[Core Rule 11.6.2](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-11-6-2): MicroProfile bundles are
located under [bundles](bundles/).

<a id="rule-mp-4-3"></a>**Rule MP-4.3 — Scope Jakarta and MicroProfile specification APIs as provided.** This rule
specializes [Core Rule 11.7](https://github.com/helidon-io/helidon/blob/main/DEV-GUIDELINES.md#rule-11-7): Jakarta EE
components and MicroProfile specification APIs use `provided` scope unless the module implements the specification.
