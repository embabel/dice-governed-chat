package example.dice.governed;

import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.dice.audit.AuditCanonicalLookup;
import com.embabel.dice.audit.AuditContextProvider;
import com.embabel.dice.audit.AuditFailurePolicy;
import com.embabel.dice.audit.AuditingPropositionRepository;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.storage.DrivineAuditLogStore;
import com.embabel.dice.storage.DrivinePropositionRepository;
import com.embabel.dice.store.ScopedReads;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.drivine.manager.PersistenceManager;
import org.drivine.query.CypherStatement;
import org.drivine.query.QuerySpecification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class Storage {

    private final PropositionRepository facts;
    private final AuditCanonicalLookup canonicalLookup;
    private final ScopedReads reads;
    private final DrivineAuditLogStore audit;
    private final PersistenceManager manager;
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final Map<String, NamedEntityDataRepository> workspaces = new ConcurrentHashMap<>();

    Storage(DrivinePropositionRepository raw, DrivineAuditLogStore audit, PersistenceManager manager) {
        this.canonicalLookup = raw.auditCanonicalLookup();
        this.facts = AuditingPropositionRepository.wrapping(raw, audit, AuditContextProvider.NONE,
                AuditFailurePolicy.FAIL_WRITE, canonicalLookup);
        this.reads = raw.scopedReads();
        this.audit = audit;
        this.manager = manager;
    }

    PropositionRepository facts() {
        return facts;
    }

    /** Uses the same duplicate key as a DICE save. Status does not affect the canonical ID. */
    Optional<Proposition> findCanonicalMatch(Proposition attempted) {
        return Optional.ofNullable(canonicalLookup.findAuditCanonicalId(attempted))
                .map(facts::findById)
                .filter(fact -> attempted.getContextIdValue().equals(fact.getContextIdValue()))
                .filter(fact -> attempted.getText().equals(fact.getText()));
    }

    ScopedReads reads() {
        return reads;
    }

    DrivineAuditLogStore audit() {
        return audit;
    }

    NamedEntityDataRepository entities(String workspace) {
        return workspaces.computeIfAbsent(workspace,
                key -> new InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("Support")));
    }

    /** The workspace marker and complete baseline are installed together on first use. */
    Instant ensureBaseline(String workspace) {
        return write(workspace, () -> manager.optionalGetOne(query("""
                MATCH (w:GovernedChatWorkspace {id: $workspace}) RETURN w.cutover AS cutover
                """, Map.of("workspace", workspace)).transform(String.class)).map(Instant::parse).orElseGet(() -> {
            var cutover = Instant.now();
            var snapshot = facts.query(PropositionQuery.againstContext(workspace));
            audit.initializeBaseline(workspace, cutover, snapshot);
            manager.execute(query("""
                    CREATE (:GovernedChatWorkspace {id: $workspace, cutover: $cutover})
                    """, Map.of("workspace", workspace, "cutover", cutover.toString())));
            return cutover;
        }));
    }

    void saveReview(String workspace, String id, Proposition fact, String reason) {
        if (!workspace.equals(fact.getContextIdValue())) {
            throw new IllegalArgumentException("Review proposition belongs to a different workspace");
        }
        var serialized = serialize(fact);
        write(workspace, () -> {
            manager.execute(query("""
                    MERGE (r:GovernedChatReview {workspace: $workspace, id: $id})
                    ON CREATE SET r.fact = $fact, r.reason = $reason
                    """, Map.of("workspace", workspace, "id", id, "fact", serialized, "reason", reason)));
            return null;
        });
    }

    List<Pending> pending(String workspace) {
        var rows = manager.query(query("""
                MATCH (r:GovernedChatReview {workspace: $workspace})
                RETURN {id: r.id, fact: r.fact, reason: r.reason} ORDER BY r.id
                """, Map.of("workspace", workspace)).transform(ReviewRow.class));
        return rows.stream().map(row -> new Pending(row.id(), deserialize(row.fact()), row.reason())).toList();
    }

    void deleteReview(String workspace, String id) {
        write(workspace, () -> {
            manager.execute(query("""
                    MATCH (r:GovernedChatReview {workspace: $workspace, id: $id}) DELETE r
                    """, Map.of("workspace", workspace, "id", id)));
            return null;
        });
    }

    void saveSource(String workspace, String sourceId, String text) {
        write(workspace, () -> {
            var existing = source(workspace, sourceId);
            if (existing.isPresent()) {
                if (!existing.orElseThrow().equals(text)) {
                    throw new IllegalArgumentException("Source identifier already refers to different content");
                }
                return null;
            }
            manager.execute(query("""
                    CREATE (:GovernedChatSource {workspace: $workspace, id: $id, text: $text})
                    """, Map.of("workspace", workspace, "id", sourceId, "text", text)));
            return null;
        });
    }

    Optional<String> source(String workspace, String sourceId) {
        return manager.optionalGetOne(query("""
                MATCH (s:GovernedChatSource {workspace: $workspace, id: $id}) RETURN s.text AS text
                """, Map.of("workspace", workspace, "id", sourceId)).transform(String.class));
    }

    <T> T write(String workspace, Supplier<T> operation) {
        return audit.duringWrite(workspace, operation);
    }

    private static QuerySpecification<Object> query(String statement, Map<String, Object> bindings) {
        return QuerySpecification.withStatement(new CypherStatement(statement)).bind(bindings);
    }

    private String serialize(Proposition fact) {
        try {
            return mapper.writeValueAsString(fact);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not encode pending review");
        }
    }

    private Proposition deserialize(String serialized) {
        try {
            return mapper.readValue(serialized, Proposition.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not read pending review");
        }
    }

    record Pending(String id, Proposition fact, String reason) {
    }

    private record ReviewRow(String id, String fact, String reason) {
    }
}
