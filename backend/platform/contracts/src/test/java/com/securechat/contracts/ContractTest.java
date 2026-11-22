package com.securechat.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ContractTest {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Path SCHEMA_DIR = Path.of("src/main/resources/schemas");

    private static Schema schema(String resource) throws IOException {
        try (InputStream in = ContractTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("schema %s", resource).isNotNull();
            return REGISTRY.getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void producerPayloadMatchesPublishedSchema(EventType type) throws IOException {
        EventEnvelope env = EventCodec.envelope(type, UUID.randomUUID(), Samples.A.toString(), Samples.of(type),
                "corr-1", null, "contract-test");
        JsonNode json = EventCodec.mapper().readTree(EventCodec.toJson(env));
        List<Error> envelopeErrors = schema("/schemas/envelope.v1.json").validate(json);
        assertThat(envelopeErrors).as("envelope").isEmpty();
        List<Error> errors = schema(type.schemaResource()).validate(json.get("payload"));
        assertThat(errors).as("%s payload", type).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void consumerRoundTripsAndIgnoresUnknownFields(EventType type) {
        Object sample = Samples.of(type);
        EventEnvelope env = EventCodec.envelope(type, UUID.randomUUID(), "agg", sample, null, null, "t");
        ObjectNode withExtra = ((ObjectNode) EventCodec.mapper().readTree(EventCodec.toJson(env)));
        ((ObjectNode) withExtra.get("payload")).put("someFutureOptionalField", "x");
        withExtra.put("someFutureEnvelopeField", 1);
        EventEnvelope parsed = EventCodec.fromJson(withExtra.toString());
        assertThat(parsed.eventType()).isEqualTo(type.wireName());
        assertThat(parsed.eventVersion()).isEqualTo(type.version());
        assertThat(EventCodec.payload(parsed, type.payloadType())).isEqualTo(sample);
    }

    /** Payload record and schema must describe the same fields (no silent drift). */
    @ParameterizedTest
    @EnumSource(EventType.class)
    void recordAndSchemaAreInSync(EventType type) throws IOException {
        JsonNode s = EventCodec.mapper().readTree(Files.readString(SCHEMA_DIR.resolve(type.schemaResource().substring(9))));
        Set<String> schemaProps = new java.util.HashSet<>();
        s.get("properties").propertyNames().forEach(schemaProps::add);
        Set<String> required = new java.util.HashSet<>();
        s.get("required").forEach(n -> required.add(n.asString()));
        Set<String> recordFields = Stream.of(type.payloadType().getRecordComponents()).map(c -> c.getName())
                .collect(Collectors.toSet());
        assertThat(recordFields).as("record fields of %s", type).containsExactlyInAnyOrderElementsOf(schemaProps);
        assertThat(recordFields).containsAll(required);
    }

    @Test
    void everySchemaFileBelongsToAKnownEventOrEnvelope() throws IOException {
        Set<String> known = Stream.of(EventType.values()).map(t -> t.schemaResource().substring(9)).collect(Collectors.toSet());
        known.add("envelope.v1.json");
        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            List<String> names = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).toList();
            for (String n : names) {
                // Older major versions may remain for parallel publication; they must still be well-formed names.
                assertThat(n).matches("[a-z.]+\\.v\\d+\\.json");
            }
            assertThat(names).containsAll(known);
        }
    }

    /**
     * Published schema versions are immutable: any edit to an existing vN file fails this test.
     * Adding a NEW version/event: run once with -Dcontracts.updateLock=true and review the diff.
     */
    @Test
    void publishedSchemasAreImmutable() throws Exception {
        TreeMap<String, String> actual = new TreeMap<>();
        try (Stream<Path> files = Files.list(SCHEMA_DIR)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
                actual.put(p.getFileName().toString(), HexFormat.of().formatHex(digest));
            }
        }
        Path lock = SCHEMA_DIR.resolve("CHECKSUMS");
        if (Boolean.getBoolean("contracts.updateLock") || !Files.exists(lock)) {
            List<String> lines = new ArrayList<>();
            if (Files.exists(lock)) {
                lines.addAll(Files.readAllLines(lock));
            }
            Set<String> locked = lines.stream().map(l -> l.split("  ")[1]).collect(Collectors.toSet());
            actual.forEach((name, sum) -> {
                if (!locked.contains(name)) {
                    lines.add(sum + "  " + name);
                }
            });
            Files.write(lock, lines);
        }
        for (String line : Files.readAllLines(lock)) {
            String[] parts = line.split("  ");
            assertThat(actual.get(parts[1])).as("schema %s changed or was removed — publish a new version instead", parts[1])
                    .isEqualTo(parts[0]);
        }
    }

    @Test
    void schemaRejectsMissingRequiredFieldsAndBadEnums() throws IOException {
        ObjectNode payload = (ObjectNode) EventCodec.tree(Samples.of(EventType.MESSAGE_ACCEPTED));
        payload.remove("messageId");
        payload.put("protocol", "PLAINTEXT");
        List<Error> errors = schema(EventType.MESSAGE_ACCEPTED.schemaResource()).validate(payload);
        assertThat(errors).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void deterministicIdsAreStableVersion5() {
        UUID m = UUID.fromString("018f2b4e-0000-7000-8000-000000000001");
        UUID d = UUID.fromString("018f2b4e-0000-7000-8000-000000000002");
        assertThat(DeterministicIds.deliveryId(m, d)).isEqualTo(DeterministicIds.deliveryId(m, d));
        assertThat(DeterministicIds.deliveryId(m, d)).isNotEqualTo(DeterministicIds.deliveryId(d, m));
        assertThat(DeterministicIds.deliveryId(m, d).version()).isEqualTo(5);
        assertThat(DeterministicIds.deliveryId(m, d).variant()).isEqualTo(2);
    }

    @Test
    void wireNamesAreUniqueAndTopicsMatch() {
        assertThat(Stream.of(EventType.values()).map(EventType::wireName).distinct().count())
                .isEqualTo(EventType.values().length);
        for (EventType t : EventType.values()) {
            assertThat(t.topic()).isEqualTo("chat." + t.wireName());
        }
    }
}
