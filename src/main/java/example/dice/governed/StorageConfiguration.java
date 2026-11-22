package example.dice.governed;

import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.storage.AuditLogSchema;
import com.embabel.dice.storage.DiceStorageSchema;
import com.embabel.dice.storage.DiceStorageSchemaKt;
import com.embabel.dice.storage.DrivineAuditLogStore;
import com.embabel.dice.storage.DrivinePropositionRepository;
import org.drivine.manager.PersistenceManager;
import org.drivine.schema.SchemaCatalog;
import org.drivine.schema.UniquenessConstraintSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {

    private static final String DATABASE = "local";

    @Bean
    static BeanFactoryPostProcessor createStorageDirectory(Environment environment) {
        return factory -> {
            var file = Path.of(environment.getRequiredProperty("database.datasources.local.database-name"));
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
            } catch (IOException failure) {
                throw new IllegalStateException("Could not create the SQLite storage directory", failure);
            }
        };
    }

    @Bean
    DiceStorageSchema auditStorageSchema() {
        return AuditLogSchema.forRegistration();
    }

    @Bean
    SchemaCatalog auditSchemaCatalog(@Qualifier("auditStorageSchema") DiceStorageSchema schema) {
        return DiceStorageSchemaKt.diceStorageCatalog(List.of(schema)).forDatabase(DATABASE);
    }

    @Bean
    SchemaCatalog governanceSchemaCatalog() {
        return SchemaCatalog.of(List.of(
                new UniquenessConstraintSpec("GovernedChatWorkspace", "id"),
                new UniquenessConstraintSpec("GovernedChatReview", List.of("workspace", "id")),
                new UniquenessConstraintSpec("GovernedChatSource", List.of("workspace", "id"))))
                .forDatabase(DATABASE);
    }

    @Bean
    DrivineAuditLogStore auditLog(PersistenceManager manager, PlatformTransactionManager transactions) {
        return new DrivineAuditLogStore(manager, transactions);
    }

    @Bean
    Storage storage(PropositionRepository raw, DrivineAuditLogStore audit, PersistenceManager manager) {
        // The starter provides the repository. Its canonical lookup follows the same deduplication as save.
        if (!(raw instanceof DrivinePropositionRepository repository)) {
            throw new IllegalStateException("Governed Chat requires the durable DICE SQLite repository");
        }
        return new Storage(repository, audit, manager);
    }
}
