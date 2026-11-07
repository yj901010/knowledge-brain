# Initial dependency review

Spring Boot and Apache Maven use Apache-2.0. Commercial use and redistribution are allowed subject to the license conditions, including preserving applicable license/NOTICE material and marking modifications. This is not a blanket clearance of all transitive dependencies or later container images.

- Spring Boot: https://github.com/spring-projects/spring-boot/blob/v4.1.1/LICENSE.txt
- Maven: https://maven.apache.org/download.cgi
- Terms: https://www.apache.org/licenses/LICENSE-2.0

Before public distribution, enumerate resolved runtime dependencies and retain their license/NOTICE obligations. No external medical dataset is bundled. MinIO, Grafana components and medical data require their own review when introduced.

Local validation uses the Microsoft Build of OpenJDK 25.0.4.1 LTS from https://learn.microsoft.com/en-us/java/openjdk/download . Its downloaded archive and legal notices stay in the tooling workspace, outside the repository; no JDK is redistributed with source. Review its bundled legal material before distributing runtime images.

## Identity/outbox additions (2026-10-06)

| Dependency | Version | License and terms |
| --- | --- | --- |
| Protobuf compiler and Java runtime | 4.36.2 (upstream v36.2) | BSD-3-Clause: commercial use and redistribution allowed with copyright, license/disclaimer preservation and no endorsement |
| Ascopes protobuf-maven-plugin | 5.1.11 | Apache-2.0: preserve applicable license/NOTICE material and modification notices |
| Spring JDBC/transactions | 7.0.9 (Boot-managed) | Apache-2.0, same conditions |
| PostgreSQL JDBC | 42.7.13 (Boot-managed) | BSD-2-Clause: preserve copyright and license/disclaimer |
| Flyway core/PostgreSQL module | 12.4.0 (Boot-managed) | Apache-2.0; this adds the open-source modules, no paid extensions |

Primary license sources reviewed before addition: [Protobuf](https://github.com/protocolbuffers/protobuf/blob/v36.2/LICENSE), [Protobuf Maven plugin](https://github.com/ascopes/protobuf-maven-plugin/blob/v5.1.11/LICENSE.txt), [Spring Framework](https://github.com/spring-projects/spring-framework/blob/v7.0.9/LICENSE.txt), [PostgreSQL JDBC](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.13/LICENSE), [Flyway](https://github.com/flyway/flyway/blob/main/LICENSE.txt).

Local Kubernetes build validation uses `maven:3.10.0-eclipse-temurin-25` pinned to `sha256:0396dcd8cd0d46a0d2026449b714b2a5bbe53cce030975b5a41f8ffa3f5f0525`. Maven is Apache-2.0; Eclipse Temurin/OpenJDK uses GPLv2 with the Classpath Exception. Preserve the image's bundled legal files when redistributing it. This validation image is not an application runtime distribution; enumerate OS/transitive dependency licenses before distributing application images. Debezium's Apache-2.0 source license was reviewed for the next CDC step, but no Debezium dependency/image is added by this change. No external medical data is added.

## CDC additions (2026-10-06)

Gateway common-origin routing reuses existing Spring MVC/Security, Micrometer and JDK 25 HTTP/TLS APIs. Its optional Jib 3.5.2 profile reuses the previously reviewed Apache-2.0 plugin and pinned Temurin image. No new runtime dependency, external dataset or Spring Cloud component is added.

Guest API step reuses existing Spring MVC/Security/JDBC and JDK cryptographic/TLS APIs. Identity adds the already reviewed HikariCP 7.0.2 and optional Jib 3.5.2, both Apache-2.0. No new authentication library or external dataset is added. The local certificate helper uses the same operator-side Python cryptography tooling used for previous Keycloak certificate bootstrap; no Python dependency is packaged into the Java runtime image. Local verifier image remains python:3.13.7-alpine, with its bundled Python/OS license notices to be preserved if redistributed.

Space now uses the already reviewed Kafka clients 4.3.1 in runtime scope; Identity transport validation retains test scope. HikariCP 7.0.2 and optional Jib Maven plugin 3.5.2 were reviewed before addition and use Apache-2.0, permitting commercial use/redistribution with applicable license/NOTICE preservation. Sources: [HikariCP license](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/LICENSE), [Jib license](https://github.com/GoogleContainerTools/jib/blob/master/LICENSE). The pinned Eclipse Temurin 25 JRE application base uses GPLv2 with the Classpath Exception; retain its legal material and inventory OS/transitive notices before public distribution. No external medical dataset is added.

Debezium 3.7.0.Final is Apache-2.0; reviewed [upstream source license](https://github.com/debezium/debezium/blob/v3.7.0.Final/LICENSE.txt). Commercial use and redistribution are allowed subject to preserving license/NOTICE material and modification notices. Connect uses the official `quay.io/debezium/connect` image pinned to multi-architecture manifest `sha256:2140b5e97a887e52d730d7ee4c12968e24f500e541d57fb37494e715acd39c36`; no image is rebuilt or redistributed by this change. Enumerate bundled JDK/OS/JDBC driver licenses before distributing a derived image; Apache-2.0 for Debezium is not a license for every bundled component.

Kafka clients 4.3.1 are added in **test scope only** for actual transport verification, under [Apache-2.0](https://github.com/apache/kafka/blob/4.3.1/LICENSE). Reviewed Maven POM license declarations for runtime dependencies: zstd-jni 1.5.6-10 BSD-2-Clause, lz4-java 1.10.2 Apache-2.0, snappy-java 1.1.10.7 Apache-2.0. SLF4J is MIT (existing Boot-managed logging dependency). Preserve copyright/license/disclaimer notices for BSD/MIT and applicable Apache NOTICE material when distributing these artifacts. No Kafka application producer dependency or external dataset is added.

## Markdown Notes (2026-10-06)

Content reuses previously reviewed Spring MVC/Security/JDBC/transactions, Boot-managed
Jackson/Micrometer, HikariCP, PostgreSQL JDBC, Flyway, Protobuf/Ascopes and optional
Jib/Temurin image versions. Kafka clients 4.3.1 remain test scope in Content. The
HTTPS authority client uses JDK HTTP/TLS. No new runtime library, Markdown parser,
external dataset or AI component is added; existing license/NOTICE obligations apply.

Wiki Links/Backlinks use an in-repository deterministic Java scanner and existing
Spring JDBC/transactions, Protobuf, Kafka test clients and Micrometer versions.
No additional runtime dependency or external dataset is introduced; existing
commercial-use and redistribution license/NOTICE obligations remain applicable.

Tag/Properties (2026-10-07) reuse existing Jackson, JDK calendar/BigDecimal, JDBC,
Protobuf and test-only Kafka clients. No dependency version, runtime library or
external dataset was added; existing license and NOTICE obligations apply.


Daily Note/Journal (2026-10-07) reuse JDK Clock/LocalDate/TZDB, existing Spring
JDBC/transactions, Jackson, Protobuf and test-only Kafka clients. No library version,
new dependency or external dataset was added; existing license/NOTICE obligations apply.

## OpenSearch read-model foundation (2026-10-07)

OpenSearch 3.9.0 uses Apache-2.0, permitting commercial use and redistribution
subject to license/NOTICE preservation. The original upstream image is pinned by
OCI digest; no image is repackaged. Preserve bundled third-party legal material
when distributing it. Upstream LICENSE/NOTICE are stored with the Helm chart.
See [opensearch-dependency-review.txt](opensearch-dependency-review.txt) for primary
sources and artifact identity. Brain Query reuses the JDK HTTP/TLS APIs, existing
Jackson and the existing internal Protobuf contract; no external Java client is added.

Brain Query Note projection (2026-10-07) reuses reviewed Spring JDBC, HikariCP,
PostgreSQL JDBC, Flyway, Kafka clients 4.3.1 and Jib/Temurin image versions. No new
external library version or dataset is added; existing license/NOTICE obligations apply.

## Cassandra / JanusGraph foundation (2026-10-07)

Cassandra 4.0.21 source uses Apache-2.0; the unmodified official OCI image is pinned
by digest in infra/dependencies/cassandra.json. Upstream LICENSE/NOTICE are retained
in infra/charts/brain-cassandra/files. JanusGraph core/cql 1.1.0 Java artifacts use
Apache-2.0; upstream documentation carries CC-BY-4.0 and is not copied into this
repository. Upstream LICENSE/NOTICE are retained in infra/dependencies/janusgraph.
Preserve applicable notices when distributing artifacts; source license declarations
do not replace a complete inventory of bundled image OS/JDK and Maven transitives.
No dataset, medical terminology, AI/parser or external Graph UI is added.

Primary sources: [Cassandra license](https://raw.githubusercontent.com/apache/cassandra/cassandra-4.0.21/LICENSE.txt),
[JanusGraph license](https://raw.githubusercontent.com/JanusGraph/janusgraph/v1.1.0/LICENSE.txt).
The supported Cassandra 4.0.x combination follows the released JanusGraph 1.1.x
compatibility table, rather than unreleased master documentation. Java 25 actual
integration tests passed; that observation is not a claim of upstream certification
for every Java 25 workload.

## Registered login (2026-10-08)

Spring Security OAuth2 JOSE uses the existing Spring Boot BOM version. Spring Security and its Nimbus JOSE JWT dependency use Apache-2.0; commercial use and redistribution are allowed with license and applicable notice retention. Sources: https://github.com/spring-projects/spring-security/blob/main/LICENSE.txt and https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/10.9.1/nimbus-jose-jwt-10.9.1.pom (resolved Nimbus 10.9.1 publisher POM, Apache-2.0 verified). No dataset was added.

The restricted user login HTTPS entry uses NGINX 1.28.3 (official Alpine image pinned by digest). NGINX uses BSD-2-Clause; commercial use and redistribution are permitted with copyright/license retention. Reviewed before adding: https://nginx.org/LICENSE and https://nginx.org/en/CHANGES-1.28. The proxy verifies the existing Keycloak TLS certificate and exposes only brain-users and login assets.

Markdown preview: pinned MIT react-markdown/remark-gfm and resolved publisher
MIT/ISC transitive declarations reviewed before use; notices retained. See
[markdown-dependency-review.md](markdown-dependency-review.md).

Learning Roadmaps reuse reviewed Spring/JDBC/transactions, HikariCP, PostgreSQL, Flyway, Protobuf, test-only Kafka 4.3.1, Jib and pinned Temurin 25 runtime versions. Existing commercial-use and license/NOTICE obligations apply; no new version, UI dependency or external dataset is introduced.

2026-10-15 Learning progress/study sessions reuse the already-reviewed Spring/JDBC/Flyway/Protobuf runtime, test-only Kafka client and existing Next/React/Playwright versions. No dependency version, UI library, external dataset or AI functionality added.

## Note learning evidence

Reuses the existing reviewed Java 25/Spring/JDBC/Flyway/PostgreSQL, Protobuf/Kafka/Apicurio, React/Next/TypeScript and Playwright dependencies and container pins. No dependency version, package, external dataset or redistributed content was added. Evidence retains only the user-authorized private Note ID/version/title and memo.

## Concept learning evidence

Reuses the reviewed Java/Spring/JDBC/Flyway/PostgreSQL, Protobuf/Kafka/Apicurio, React/Next/TypeScript and Playwright dependencies and current pinned containers. No new dependency, dataset, distributed medical terminology or license. Source metadata is retained only after the owning Knowledge service authorizes a same-private-Space or public reference.

Media learning evidence reuses existing Spring/JDBC/Flyway, Protobuf/Kafka, Next/React and browser Web Crypto dependencies and pinned runtime images; no new package or dataset.

Evidence-to-study-record associations reuse existing Learning/Spring/JDBC/Flyway/Protobuf/Kafka and Next/React dependencies, with no additional package or dataset.

## Activity timeline and ClickHouse (2026-10-15)

ClickHouse official LTS 26.8.27.9 is pinned by manifest SHA-256. Its exact release LICENSE was inspected before installation: Apache-2.0 permits commercial use and redistribution with license/applicable notice retention. The release source has no root NOTICE file; none is invented. Retain bundled third-party/image/OS notices and inventory them before redistributing a container image. See [metadata](../infra/dependencies/clickhouse.json) and [retained license](../infra/dependencies/clickhouse/LICENSE). Activity uses the already-reviewed Kafka 4.3.1 client at runtime (Apache-2.0), the existing JDBC/Flyway/Protobuf/Spring stack, JDK HTTPS, and existing Next/React dependencies. No new UI package or dataset.

Private Planning goals reuse current Java 25/Spring/JDBC/Hikari/Flyway/PostgreSQL, Protobuf, test-only Kafka 4.3.1, reviewed Jib/Temurin pins and Next/React/TypeScript/Playwright. Existing TLS provisioning tooling is reused. No dependency version, UI library, medical terminology or external dataset is added.

Private Task management reuses the reviewed Planning/Graph Web dependencies and existing pinned container images. No dependency version or dataset was added.

One-time Schedule management uses the reviewed Planning/Graph Web stack and bundled Java ZoneRules. No library version, dataset or external calendar dependency was added.


Planning activity timeline reuses current Java/Spring/JDBC/Kafka/Protobuf/Next and the already reviewed ClickHouse image. No dependency version, external dataset or AI feature was added.

Finite DAILY/WEEKLY recurring schedules now use the same Planning ownership and unchanged Schedule v1: [private recurrence](private-recurrences.md). No dependency version, dataset or AI feature was added.


Private Reminder settings reuse the reviewed Planning/JDBC/Flyway/Protobuf and browser stack. No dependency version, dataset or AI functionality was added.


Notification delivery reuses the already reviewed JDBC/Hikari/Flyway, Protobuf and Kafka clients 4.3.1 as runtime dependencies and existing pinned Java/Jib images. No new library version, external dataset or AI was added.

## Private Groups reuse (2026-10-22)

Collaboration reuses the already reviewed Spring Boot/JDBC, HikariCP 7.0.2, PostgreSQL JDBC 42.7.13, Flyway 12.4.0, Protobuf 4.36.2, Java 25 and Jib 3.5.2 baseline. Kafka clients 4.3.1 are test-scope only in Collaboration for actual Outbox transport checks. No new library version, external dataset, terminology content or AI dependency is introduced. Existing upstream license/NOTICE requirements and generated web notices continue to apply.

Public GitHub integration (2026-11-01) reuses the reviewed Planning JDBC/HikariCP/PostgreSQL/Flyway, platform Protobuf contracts and test-only Kafka clients at the identical pinned versions above. No new library versions or GitHub SDK are added; outbound HTTPS uses JDK25. Existing commercial-use/redistribution conditions and license/NOTICE retention apply. No source repository or external dataset is bundled; limited private imported commit metadata does not authorize redistributing third-party repository content.

Connect image repin (2026-11-01): the former official manifest 79265cb2c2b9d901bf3c24f4e09e40d06f14987b6bcba0f6e0e06d3f1ddc1fbb returned manifest-not-found during the approved fresh rebuild. The current official3.7.0.Final tag is pinned to 2140b5e97a887e52d730d7ee4c12968e24f500e541d57fb37494e715acd39c36; its linux/amd64 manifest is 395b05697d41bc65709f04287b166e75906f86846c792f5a175a49235577a553. OCI environment independently confirms DEBEZIUM_VERSION=3.7.0.Final and KAFKA_VERSION=4.3.1. This changes image identity, not the declared Debezium version or service/event architecture. Same Apache-2.0 component license review applies; the image includes OS/JDK/driver components with their own notices, and public redistribution/SBOM clearance is still separate. Primary references: [official release](https://debezium.io/releases/3.7/), [license](https://github.com/debezium/debezium/blob/v3.7.0.Final/LICENSE.txt). Runtime compatibility must pass fresh Connect/Kafka byte/header checks before completion.

Local backup/recovery (2026-11-01) reuses existing operator-side Python cryptography44.0.3 (Apache-2.0 OR BSD-3-Clause), standard-library archive/process tools and existing Docker/k3d/kubectl plus previously reviewed validation images. No new Java/Node dependency, runtime package, medical dataset or version change is introduced. Encrypted private backups preserve existing image/source/license files; public redistribution clearance remains separate.

## Observability (2026-11-01)

Official digest-pinned OpenTelemetry Java agent2.33.1/Collector0.164.0, Prometheus3.15.1 and Alertmanager0.34.2 use Apache-2.0. Gateway adds OpenTelemetry API/context1.67.0 under Apache-2.0. Grafana OSS13.3.0, Loki3.7.9 and maintained Tempo2.10.10 use AGPL-3.0-only. Exact upstream LICENSE files were retained and commercial-use/redistribution conditions checked before installation. Apache licenses require preservation of licenses and applicable notices. AGPL permits commercial use subject to license/corresponding-source obligations, including the section13 source offer for modified network-served programs. The official monitoring images are unmodified; retain their third-party/OS notices and exact source references, and complete image/SBOM/source-offer checks before public redistribution. This is not an assertion that unrelated platform code changes license. See [versions, digests and upstream sources](../infra/dependencies/observability.json) and [retained licenses](../infra/dependencies/observability). Existing Python/cryptography, Strimzi/Kafka exporter and Playwright validation dependencies are reused. No UI library, medical terminology or dataset was added.

Public knowledge workbench (2026-11-01) reuses the reviewed Java25/Spring/JDBC/Hikari/Flyway/PostgreSQL, Jackson, Protobuf/Kafka/Apicurio, Next/React/TypeScript, Python/PyYAML and Playwright dependencies at the existing pins. SHA256 uses built-in Java/PostgreSQL functions, with no new extension. Existing commercial-use/redistribution conditions and retained notices still apply. Source records require a submitted license/use note, which is provenance to review rather than automatic redistribution clearance. No external dataset, medical terminology content, full text, package or version was imported; browser fixtures contain original Development examples only. See [scope and governance](public-knowledge-workbench.md).


## Public candidate cache (2026-11-06)

Valkey9.0.7 BSD-3-Clause and official io.valkey:valkey-java5.5.0 MIT source notices
were verified at exact releases before integration. Client runtime dependencies
are Commons Pool2.13.1/Gson2.13.2 Apache-2.0, SLF4J2.0.20 MIT and JSON20240303
Public Domain at its exact release. Optional resilience dependencies are absent.
Preserve license/copyright notices; the application image includes SDK/JSON/Gson
notices in its legal directory and the other JARs retain their embedded notices.
The official unmodified Valkey/Alpine image retains bundled component/OS notices;
no third-party dataset or restricted terminology is added. Both exact Valkey/Query
artifact HIGH/CRITICAL scans passed without waivers. Source/license hashes, image
index/amd64 pins and direct upstream references are in
[the lock](../infra/dependencies/valkey/lock.json) and
[the runbook](public-search-cache.md). These scoped inventory checks are not an
assertion of complete infrastructure security or blanket third-party clearance.

## Workspace accessibility tooling and Next maintenance (2026-11-07)

Unmodified development-only @axe-core/playwright4.14.0 and axe-core4.14.0
use MPL-2.0; bundled third-party notices include MIT/ISC components. Commercial
use is permitted subject to the MPL notice/source obligations for covered files.
The exact package integrity, tar/license hashes, source references and retained
license texts are in [the review lock](../infra/dependencies/accessibility/lock.json).
No axe code is imported into application modules or the static product export.
Keep these notices with redistributed tooling; this does not change the license
of unrelated application code.

Next is updated within16.4 from16.4.0 to16.4.5 under the same MIT license, whose
exact release text and hash are retained in that lock/directory. Publisher
advisories and the original dependency audit motivated the update; the new locked
dependency audit reports zero detections. Static export remains the runtime:
there is no deployed Next server/image optimizer, and no application exploitability
or general security-certification claim follows from the audit. Existing runtime
MIT notices and generated third-party notices are preserved. No new runtime
library or external dataset is introduced by the native graph Worker/table work.

## Sourced authoring drafts (2026-11-07)

The separate [public-curation pack](../data/public-curation/README.md) contains
original concise Korean factual paraphrases, source/section/date links and scoped
rights notes. It does not redistribute an external terminology dataset, source
page, illustration, code example or guideline. The checked primary policies are
[NCI text reuse](https://www.cancer.gov/policies/copyright-reuse), the
[PostgreSQL documentation license](https://www.postgresql.org/about/licence/) and
[IETF TLP 5.0](https://trustee.ietf.org/documents/trust-legal-provisions/tlp-5/).
Copyright-marked NCI material/images are excluded; attribution and the required
translation nonendorsement remain. IETF modification restrictions are recorded,
not treated as permission to relicense RFC text. All items remain unpublished and
independently unreviewed, with no project license assignment or blanket legal/
clinical clearance claim. Eight integrity tests enforce the authoring boundaries;
they do not establish factual or legal review. No new software dependency is added.
