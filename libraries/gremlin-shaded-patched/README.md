# Gremlin dependency compatibility build

This is an `io.knowledgebrain` artifact, not an Apache TinkerPop release. It
retains the Gremlin3.7.3 relocation layout and Kryo/minlog/Objenesis versions,
while replacing actual embedded Jackson2.15.2 with2.18.11. The standalone
Spring Boot Jackson versions remain independent. Merely overriding the root
Jackson BOM cannot replace classes already relocated inside another JAR.

The original POM, license/notice files and commit/hash provenance are in
`src/main/resources/META-INF`. Additional Jackson MIT notices remain in the
dependency JAR and are carried through the shade operation. Service descriptors
are excluded as in the original artifact; all classes remain unminimized.

Build through the root Maven `verify` lifecycle so this module is packaged
before Brain Query compiles/tests. Its execution overrides Boot's inherited
`default` shade execution to avoid two consecutive shade operations.

Before removing this compatibility artifact, verify that the proposed upstream
release actually replaces its embedded Jackson, then run the graph serializer
tests, original-writer compatibility proof and real Cassandra persistence,
ordering/concurrency and Space-isolation checks. Keep the original upstream
exclusions until a complete replacement is verified.
