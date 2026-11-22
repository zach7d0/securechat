package com.securechat.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.Topics;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationServiceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "conversation_db");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
    }

    static final Map<UUID, List<UUID>> DEVICES = new ConcurrentHashMap<>();
    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    UserDirectory users;
    @Autowired
    HistoryShareService shares;
    Http http;

    record U(UUID id, UUID device, String token) {
    }

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
        when(users.existing(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(users.activeDevices(anyList())).thenAnswer(inv -> {
            Map<UUID, List<UUID>> m = new HashMap<>();
            for (UUID u : inv.<List<UUID>>getArgument(0)) {
                m.put(u, DEVICES.getOrDefault(u, List.of()));
            }
            return m;
        });
    }

    U user() {
        UUID id = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        DEVICES.put(id, List.of(d));
        return new U(id, d, TestTokens.token(id, "u", d));
    }

    static String blob() {
        return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
    }

    String group(U owner) {
        Http.Response r = http.post("/api/v1/groups", Map.of("name", "Team", "description", "d"), owner.token());
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("group").get("epoch").asLong()).isZero();
        return r.text("id");
    }

    Http.Response add(U actor, String gid, long epoch, U... members) {
        List<UUID> ids = new ArrayList<>();
        List<Map<String, Object>> welcomes = new ArrayList<>();
        for (U m : members) {
            ids.add(m.id());
            welcomes.add(Map.of("deviceId", m.device(), "keyPackageId", UUID.randomUUID(), "welcome", blob()));
        }
        return http.post("/api/v1/groups/" + gid + "/members", Map.of("userIds", ids, "commit", blob(), "epoch", epoch,
                "welcomes", welcomes), actor.token());
    }

    @Test
    void directConversationIsUniquePerPairEvenUnderConcurrency() throws Exception {
        U a = user();
        U b = user();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Http.Response>> fs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            U who = i % 2 == 0 ? a : b;
            U peer = who == a ? b : a;
            fs.add(pool.submit(() -> http.post("/api/v1/conversations/direct", Map.of("peerUserId", peer.id()), who.token())));
        }
        Set<String> ids = new HashSet<>();
        for (var f : fs) {
            Http.Response r = f.get();
            assertThat(r.status()).isEqualTo(200);
            ids.add(r.text("id"));
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        UUID low = ConversationService.pairOrder(a.id(), b.id()) < 0 ? a.id() : b.id();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM direct_conversation_pairs WHERE user_low = ?", Long.class, low)).isEqualTo(1L);
        assertThat(http.post("/api/v1/conversations/direct", Map.of("peerUserId", a.id()), a.token()).code()).isEqualTo("VALIDATION_FAILED");
        // a third user cannot read it
        assertThat(http.get("/api/v1/conversations/" + ids.iterator().next(), user().token()).status()).isEqualTo(404);
    }

    /** Regression: UUIDs whose high bit differs order differently as signed longs vs unsigned bytes. */
    @Test
    void directConversationWorksForUuidsThatOrderDifferentlySignedAndUnsigned() {
        UUID hi = UUID.fromString("f0000000-0000-4000-8000-000000000001");
        UUID lo = UUID.fromString("10000000-0000-4000-8000-000000000001");
        assertThat(hi.compareTo(lo)).isNegative();          // signed comparison says hi < lo …
        assertThat(ConversationService.pairOrder(hi, lo)).isPositive();   // … byte order says hi > lo
        DEVICES.put(hi, List.of(UUID.randomUUID()));
        DEVICES.put(lo, List.of(UUID.randomUUID()));
        Http.Response a = http.post("/api/v1/conversations/direct", Map.of("peerUserId", lo), TestTokens.token(hi, "h", null));
        Http.Response b = http.post("/api/v1/conversations/direct", Map.of("peerUserId", hi), TestTokens.token(lo, "l", null));
        assertThat(a.status()).isEqualTo(200);
        assertThat(b.text("id")).isEqualTo(a.text("id"));
    }

    @Test
    void groupLifecycleWithEpochCompareAndSet() {
        U owner = user();
        U bob = user();
        U carol = user();
        String gid = group(owner);
        Http.Response added = add(owner, gid, 0, bob, carol);
        assertThat(added.status()).isEqualTo(200);
        assertThat(added.body().get("members").size()).isEqualTo(3);
        assertThat(added.body().get("group").get("epoch").asLong()).isEqualTo(1);
        // Bob's device has a pending Welcome at epoch 1
        JsonNode w = http.get("/api/v1/groups/welcomes", bob.token()).body();
        assertThat(w.size()).isEqualTo(1);
        assertThat(w.get(0).get("epoch").asLong()).isEqualTo(1);
        assertThat(http.delete("/api/v1/groups/welcomes/" + w.get(0).get("id").asString(), bob.token()).status()).isEqualTo(204);
        assertThat(http.get("/api/v1/groups/welcomes", bob.token()).body().size()).isZero();
        // stale epoch rejected with the current epoch
        Http.Response stale = http.post("/api/v1/groups/" + gid + "/commits", Map.of("commit", blob(), "epoch", 0), bob.token());
        assertThat(stale.code()).isEqualTo("EPOCH_CONFLICT");
        assertThat(stale.body().get("currentEpoch").asLong()).isEqualTo(1);
        // key update by a member
        assertThat(http.post("/api/v1/groups/" + gid + "/commits", Map.of("commit", blob(), "epoch", 1, "kind", "UPDATE"), bob.token())
                .body().get("epoch").asLong()).isEqualTo(2);
        JsonNode commits = http.get("/api/v1/groups/" + gid + "/commits?fromEpoch=0", carol.token()).body();
        assertThat(commits.size()).isEqualTo(2);
        assertThat(commits.get(0).get("epoch").asLong()).isZero();
        assertThat(commits.get(1).get("kind").asString()).isEqualTo("UPDATE");
    }

    @Test
    void concurrentCommitsOnSameEpochOnlyOneWins() throws Exception {
        U owner = user();
        U bob = user();
        String gid = group(owner);
        add(owner, gid, 0, bob);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Http.Response>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            U who = i % 2 == 0 ? owner : bob;
            fs.add(pool.submit(() -> http.post("/api/v1/groups/" + gid + "/commits", Map.of("commit", blob(), "epoch", 1, "kind", "UPDATE"),
                    who.token())));
        }
        int ok = 0;
        int conflict = 0;
        for (var f : fs) {
            Http.Response r = f.get();
            if (r.status() == 200) {
                ok++;
            } else if ("EPOCH_CONFLICT".equals(r.code())) {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT epoch FROM group_metadata WHERE conversation_id = ?::uuid", Long.class, gid)).isEqualTo(2L);
    }

    @Test
    void authorizationOfMembershipOperations() {
        U owner = user();
        U bob = user();
        U carol = user();
        U outsider = user();
        String gid = group(owner);
        add(owner, gid, 0, bob);
        assertThat(add(bob, gid, 1, carol).code()).isEqualTo("FORBIDDEN");                  // plain member cannot add
        assertThat(add(outsider, gid, 1, carol).status()).isEqualTo(404);                     // non-member
        assertThat(http.get("/api/v1/groups/" + gid + "/commits", outsider.token()).status()).isEqualTo(404);
        assertThat(http.patch("/api/v1/groups/" + gid, Map.of("name", "x"), bob.token()).code()).isEqualTo("FORBIDDEN");
        assertThat(http.delete("/api/v1/groups/" + gid + "/members/" + owner.id(), bob.token()).status()).isEqualTo(400); // body required
        // device-bound token required for commits
        String unbound = TestTokens.token(owner.id(), "o", null);
        assertThat(http.post("/api/v1/groups/" + gid + "/commits", Map.of("commit", blob(), "epoch", 1), unbound).code())
                .isEqualTo("DEVICE_REQUIRED");
        // promote bob to ADMIN → can add
        assertThat(http.put("/api/v1/groups/" + gid + "/members/" + bob.id() + "/role", Map.of("role", "ADMIN"), owner.token()).status())
                .isEqualTo(200);
        assertThat(add(bob, gid, 1, carol).status()).isEqualTo(200);
        // admin cannot remove the owner
        assertThat(http.call("DELETE", "/api/v1/groups/" + gid + "/members/" + owner.id(), Map.of("commit", blob(), "epoch", 2), bob.token(),
                Map.of()).code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void welcomeForForeignDeviceRollsBackWholeChange() {
        U owner = user();
        U bob = user();
        String gid = group(owner);
        Http.Response r = http.post("/api/v1/groups/" + gid + "/members", Map.of("userIds", List.of(bob.id()), "commit", blob(), "epoch", 0,
                "welcomes", List.of(Map.of("deviceId", UUID.randomUUID(), "welcome", blob()))), owner.token());
        assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
        Http.Response g = http.get("/api/v1/conversations/" + gid, owner.token());
        assertThat(g.body().get("group").get("epoch").asLong()).isZero();
        assertThat(g.body().get("members").size()).isEqualTo(1);
    }

    @Test
    void removedMembersAreExcludedFromFutureResolution() {
        U owner = user();
        U bob = user();
        U carol = user();
        String gid = group(owner);
        add(owner, gid, 0, bob, carol);
        Http.Response rm = http.call("DELETE", "/api/v1/groups/" + gid + "/members/" + carol.id(), Map.of("commit", blob(), "epoch", 1),
                owner.token(), Map.of());
        assertThat(rm.status()).isEqualTo(200);
        assertThat(rm.body().get("members").size()).isEqualTo(2);
        JsonNode auth = http.internal("GET", "/internal/v1/conversations/" + gid + "/authorize?userId=" + carol.id(), null).body();
        assertThat(auth.get("member").asBoolean()).isFalse();
        assertThat(auth.get("memberUserIds").toString()).doesNotContain(carol.id().toString());
        assertThat(auth.get("epoch").asLong()).isEqualTo(2);
        assertThat(http.get("/api/v1/conversations/" + gid, carol.token()).status()).isEqualTo(404);
        assertThat(http.get("/api/v1/groups/" + gid + "/commits", carol.token()).status()).isEqualTo(404);
        assertThat(http.internal("GET", "/internal/v1/users/" + carol.id() + "/peers", null).body().size()).isZero();
        // re-adding works and resets the join epoch
        Http.Response again = add(owner, gid, 2, carol);
        assertThat(again.status()).isEqualTo(200);
    }

    @Test
    void ownerLeavingHandsOverOwnership() {
        U owner = user();
        U bob = user();
        String gid = group(owner);
        add(owner, gid, 0, bob);
        assertThat(http.post("/api/v1/groups/" + gid + "/leave", null, owner.token()).status()).isEqualTo(204);
        JsonNode g = http.get("/api/v1/conversations/" + gid, bob.token()).body();
        assertThat(g.get("myRole").asString()).isEqualTo("OWNER");
        assertThat(g.get("members").size()).isEqualTo(1);
    }

    @Test
    void membershipEventsArePublishedReliably() {
        U owner = user();
        U bob = user();
        String gid = group(owner);
        add(owner, gid, 0, bob);
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Set<String> seen = ConcurrentHashMap.newKeySet();
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.CONVERSATION_CREATED, Topics.GROUP_MEMBER_ADDED, Topics.GROUP_COMMIT));
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var rec : c.poll(Duration.ofMillis(500))) {
                    var env = EventCodec.fromJson(rec.value());
                    if (env.aggregateId().equals(gid) || env.aggregateId().startsWith(gid + ":")) {
                        seen.add(env.eventType());
                        if (env.eventType().equals("group.member.added")) {
                            assertThat(env.payload().get("membershipVersion").asLong()).isEqualTo(2);
                            assertThat(env.payload().get("epoch").asLong()).isEqualTo(1);
                        }
                    }
                }
                return seen.containsAll(List.of("conversation.created", "group.member.added", "group.commit"));
            });
        }
    }

    @Test
    void historySharesRelayCiphertextToCurrentMembersOnly() {
        U owner = user();
        U member = user();
        U newcomer = user();
        U outsider = user();
        String gid = group(owner);
        assertThat(add(owner, gid, 0, member).status()).isEqualTo(200);
        assertThat(add(owner, gid, 1, newcomer).status()).isEqualTo(200);
        String base = "/api/v1/groups/" + gid + "/history-shares";

        // only OWNER/ADMIN may create; outsiders do not learn the group exists
        assertThat(http.post(base, null, member.token()).status()).isEqualTo(403);
        assertThat(http.post(base, null, outsider.token()).status()).isEqualTo(404);
        Http.Response created = http.post(base, null, owner.token());
        assertThat(created.status()).isEqualTo(201);
        String sid = created.text("id");

        byte[] c0 = new byte[1000];
        byte[] c1 = new byte[10];
        new java.util.Random(1).nextBytes(c0);
        new java.util.Random(2).nextBytes(c1);
        String b0 = Base64.getEncoder().encodeToString(c0);
        // only the creating device uploads
        U ownerOtherDevice = new U(owner.id(), UUID.randomUUID(), TestTokens.token(owner.id(), "u", UUID.randomUUID()));
        assertThat(http.put(base + "/" + sid + "/chunks/0", Map.of("data", b0), ownerOtherDevice.token()).status()).isEqualTo(403);
        assertThat(http.put(base + "/" + sid + "/chunks/0", Map.of("data", b0), owner.token()).status()).isEqualTo(204);
        assertThat(http.put(base + "/" + sid + "/chunks/0", Map.of("data", b0), owner.token()).status()).isEqualTo(409);
        assertThat(http.put(base + "/" + sid + "/chunks/1", Map.of("data", "not base64!"), owner.token()).status()).isEqualTo(400);
        assertThat(http.put(base + "/" + sid + "/chunks/999", Map.of("data", b0), owner.token()).status()).isEqualTo(400);
        // not downloadable before completion; completion needs every chunk
        assertThat(http.get(base + "/" + sid + "/chunks/0", newcomer.token()).status()).isEqualTo(404);
        assertThat(http.post(base + "/" + sid + "/complete", Map.of("chunkCount", 2), owner.token()).status()).isEqualTo(400);
        assertThat(http.put(base + "/" + sid + "/chunks/1", Map.of("data", Base64.getEncoder().encodeToString(c1)), owner.token())
                .status()).isEqualTo(204);
        assertThat(http.post(base + "/" + sid + "/complete", Map.of("chunkCount", 2), owner.token()).status()).isEqualTo(204);
        assertThat(http.put(base + "/" + sid + "/chunks/2", Map.of("data", b0), owner.token()).status()).isEqualTo(409); // sealed

        // any current member downloads; outsiders and other groups' ids get 404
        Http.Response got = http.get(base + "/" + sid + "/chunks/0", newcomer.token());
        assertThat(got.status()).isEqualTo(200);
        assertThat(Base64.getDecoder().decode(got.text("data"))).isEqualTo(c0);
        assertThat(http.get(base + "/" + sid + "/chunks/1", member.token()).status()).isEqualTo(200);
        assertThat(http.get(base + "/" + sid + "/chunks/0", outsider.token()).status()).isEqualTo(404);
        assertThat(http.get(base + "/" + sid + "/chunks/5", newcomer.token()).status()).isEqualTo(404);
        String other = group(owner);
        assertThat(http.get("/api/v1/groups/" + other + "/history-shares/" + sid + "/chunks/0", owner.token()).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT total_bytes FROM history_shares WHERE id = ?::uuid", Long.class, sid)).isEqualTo(1010);

        // removed members lose access
        assertThat(http.call("DELETE", "/api/v1/groups/" + gid + "/members/" + member.id(), Map.of("commit", blob(), "epoch", 2),
                owner.token(), Map.of()).status()).isEqualTo(200);
        assertThat(http.get(base + "/" + sid + "/chunks/0", member.token()).status()).isEqualTo(404);

        // direct conversations cannot have shares; open-share cap
        String direct = http.post("/api/v1/conversations/direct", Map.of("peerUserId", outsider.id()), owner.token()).text("id");
        assertThat(http.post("/api/v1/groups/" + direct + "/history-shares", null, owner.token()).status()).isEqualTo(400);
        for (int i = 0; i < 3; i++) {
            assertThat(http.post(base, null, owner.token()).status()).isEqualTo(201);
        }
        assertThat(http.post(base, null, owner.token()).status()).isEqualTo(409);

        // expiry purge removes share and chunks
        jdbc.update("UPDATE history_shares SET expires_at = now() - interval '1 minute' WHERE conversation_id = ?::uuid", gid);
        assertThat(http.get(base + "/" + sid + "/chunks/0", newcomer.token()).status()).isEqualTo(404);
        shares.purgeExpired();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM history_share_chunks WHERE share_id = ?::uuid", Long.class, sid)).isZero();
    }

    Http.Response addAs(U actor, String gid, long epoch, String role, U m) {
        return http.post("/api/v1/groups/" + gid + "/members", Map.of("userIds", List.of(m.id()), "commit", blob(), "epoch", epoch,
                "welcomes", List.of(Map.of("deviceId", m.device(), "keyPackageId", UUID.randomUUID(), "welcome", blob())), "role", role),
                actor.token());
    }

    String roleOf(Http.Response conv, U u) {
        for (var m : conv.body().get("members")) {
            if (m.get("userId").asString().equals(u.id().toString())) {
                return m.get("role").asString();
            }
        }
        return null;
    }

    @Test
    void ownerChoosesTheRoleOfInviteesAndAdminsMayOnlyInviteMembers() {
        U owner = user();
        U admin = user();
        U m1 = user();
        U m2 = user();
        U m3 = user();
        String gid = group(owner);

        Http.Response r = addAs(owner, gid, 0, "ADMIN", admin);
        assertThat(r.status()).isEqualTo(200);
        assertThat(roleOf(r, admin)).isEqualTo("ADMIN");
        assertThat(roleOf(addAs(owner, gid, 1, "MEMBER", m1), m1)).isEqualTo("MEMBER");
        // default (no role) stays MEMBER
        Http.Response d = add(owner, gid, 2, m2);
        assertThat(roleOf(d, m2)).isEqualTo("MEMBER");

        // an ADMIN may invite members but never admins; nothing changes when refused (epoch unchanged)
        Http.Response refused = addAs(admin, gid, 3, "ADMIN", m3);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT epoch FROM group_metadata WHERE conversation_id = ?::uuid", Long.class, gid)).isEqualTo(3);
        assertThat(roleOf(addAs(admin, gid, 3, "MEMBER", m3), m3)).isEqualTo("MEMBER");
        // a MEMBER cannot invite at all; invalid roles are rejected
        assertThat(addAs(m1, gid, 4, "MEMBER", user()).status()).isEqualTo(403);
        assertThat(addAs(owner, gid, 4, "OWNER", user()).status()).isEqualTo(400);

        // promote / demote: owner only
        String roleUrl = "/api/v1/groups/" + gid + "/members/" + m1.id() + "/role";
        assertThat(http.put(roleUrl, Map.of("role", "ADMIN"), admin.token()).status()).isEqualTo(403);
        assertThat(roleOf(http.put(roleUrl, Map.of("role", "ADMIN"), owner.token()), m1)).isEqualTo("ADMIN");
        assertThat(roleOf(http.put(roleUrl, Map.of("role", "MEMBER"), owner.token()), m1)).isEqualTo("MEMBER");
    }

    @Test
    void historyGrantsAreCreatedByAdminsAndClaimedByExactlyOneMember() throws Exception {
        U owner = user();
        U member = user();
        U member2 = user();
        U newcomer = user();
        U outsider = user();
        String gid = group(owner);
        assertThat(add(owner, gid, 0, member, member2).status()).isEqualTo(200);
        assertThat(add(owner, gid, 1, newcomer).status()).isEqualTo(200);
        String base = "/api/v1/groups/" + gid + "/history-grants";

        assertThat(http.post(base, Map.of("scope", "7d"), member.token()).status()).isEqualTo(403);
        assertThat(http.post(base, Map.of("scope", "forever"), owner.token()).status()).isEqualTo(400);
        assertThat(http.post(base, Map.of("scope", "7d"), outsider.token()).status()).isEqualTo(404);
        String grant = http.post(base, Map.of("scope", "7d"), owner.token()).text("id");
        assertThat(http.get(base + "/" + grant, member.token()).body().get("claimed").asBoolean()).isFalse();
        assertThat(http.get(base + "/" + grant, outsider.token()).status()).isEqualTo(404);

        // members race to claim: exactly one wins
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Http.Response>> fs = new ArrayList<>();
        for (U u : List.of(member, member2, owner)) {
            fs.add(pool.submit(() -> http.post(base + "/" + grant + "/claim", null, u.token())));
        }
        List<Integer> statuses = new ArrayList<>();
        String share = null;
        U winner = null;
        List<U> who = List.of(member, member2, owner);
        for (int i = 0; i < 3; i++) {
            Http.Response r = fs.get(i).get();
            statuses.add(r.status());
            if (r.status() == 200) {
                share = r.text("shareId");
                winner = who.get(i);
            }
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409);
        assertThat(http.get(base + "/" + grant, newcomer.token()).body().get("claimed").asBoolean()).isTrue();

        // the claimer (not necessarily an admin) uploads; the newcomer downloads
        String sb = "/api/v1/groups/" + gid + "/history-shares/" + share;
        U loser = winner == member ? member2 : member;
        assertThat(http.put(sb + "/chunks/0", Map.of("data", blob()), loser.token()).status()).isEqualTo(403);
        assertThat(http.put(sb + "/chunks/0", Map.of("data", blob()), winner.token()).status()).isEqualTo(204);
        assertThat(http.post(sb + "/complete", Map.of("chunkCount", 1), winner.token()).status()).isEqualTo(204);
        assertThat(http.get(sb + "/chunks/0", newcomer.token()).status()).isEqualTo(200);
        assertThat(http.post(base + "/" + grant + "/claim", null, outsider.token()).status()).isEqualTo(404);

        // expired grants cannot be claimed
        String g2 = http.post(base, Map.of("scope", "all"), owner.token()).text("id");
        jdbc.update("UPDATE history_grants SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", g2);
        assertThat(http.post(base + "/" + g2 + "/claim", null, member.token()).status()).isEqualTo(404);
    }

    @Test
    void anyMemberCanSetTheDisappearingTimer() {
        U a = user();
        U b = user();
        U outsider = user();
        String direct = http.post("/api/v1/conversations/direct", Map.of("peerUserId", b.id()), a.token()).text("id");
        String url = "/api/v1/conversations/" + direct + "/disappearing";
        Http.Response on = http.put(url, Map.of("seconds", 3600), b.token());
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.body().get("disappearingSeconds").asInt()).isEqualTo(3600);
        assertThat(http.get("/api/v1/conversations/" + direct, a.token()).body().get("disappearingSeconds").asInt()).isEqualTo(3600);
        assertThat(http.put(url, Map.of("seconds", 42), a.token()).status()).isEqualTo(400);
        assertThat(http.put(url, Map.of("seconds", 300), outsider.token()).status()).isEqualTo(404);
        Http.Response off = http.put(url, Map.of("seconds", 0), a.token());
        assertThat(off.body().get("disappearingSeconds").isNull()).isTrue();

        // groups: a plain MEMBER may change it too
        U owner = user();
        U member = user();
        String gid = group(owner);
        assertThat(add(owner, gid, 0, member).status()).isEqualTo(200);
        assertThat(http.put("/api/v1/conversations/" + gid + "/disappearing", Map.of("seconds", 2592000), member.token())
                .body().get("disappearingSeconds").asInt()).isEqualTo(2592000);
    }
}
