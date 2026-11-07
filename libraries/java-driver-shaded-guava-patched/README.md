# Driver Guava compatibility backport

This own-GAV artifact preserves the existing driver4.17/JanusGraph1.1 graph
stack. It replaces the original relocated Guava `MapMakerInternalMap` family
with the original25.1 source plus the upstream33.7.2 deserialization capacity
clamp. All45 nested/outer class definitions are rebuilt together with Java8
classfile conventions. The1946-class inventory is retained; every class outside
that family, including both Graal substitutions, remains byte-identical.

The original standalone Guava25.1 dependency is compile-only input to this
compatibility build. Its classes and annotation dependencies are excluded from
the output bundle. Both input dependencies are optional: Brain Query receives
only this module's artifact. The original bundle's45 class definitions are
explicitly filtered before the rebuilt family is relocated into the same
namespace. Source/license headers, modification notices and exact source/archive
hashes are retained. This is not a Google, DataStax or Apache Cassandra release.

Build through root `mvn verify`. Verification must include weak-key and
weak-value maps: ordinary strong `MapMaker.makeMap()` returns a JDK map and
does not exercise this boundary. Use a bounded child JVM for huge/negative/
truncated serialized counts, legitimate maps above256 entries, original-writer
compatibility and original serialization IDs. Also inspect the final runtime
JAR for the exact class inventory, no old equivalent bundle, and the owning
Cassandra integration tests. Package presence alone does not demonstrate a
reachable arbitrary-deserialization endpoint in the application.

Do not replace this with a newer driver bundle solely by version number.
Official Apache driver4.19.4 still bundles Guava33.3.1; the relevant source
needs independent verification before retiring the backport. References:
[Guava advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94),
[DataStax artifact](https://repo.maven.apache.org/maven2/com/datastax/oss/java-driver-shaded-guava/maven-metadata.xml),
[Apache successor](https://repo.maven.apache.org/maven2/org/apache/cassandra/java-driver-guava-shaded/maven-metadata.xml).
