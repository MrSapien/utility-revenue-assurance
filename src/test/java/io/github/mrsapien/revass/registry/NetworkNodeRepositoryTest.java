package io.github.mrsapien.revass.registry;

import io.github.mrsapien.revass.common.NetworkDomain;
import io.github.mrsapien.revass.common.NetworkLevel;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Transactional
class NetworkNodeRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    NetworkNodeRepository repository;

    @Autowired
    EntityManager entityManager;

    UUID utility = UUID.randomUUID();
    LocalDate start = LocalDate.of(2020, 4, 1);

    @Test
    void savesATreeAndReadsItBackFromTheDatabase() {

        NetworkNode substation = new NetworkNode(utility, null, NetworkDomain.ELECTRICITY,
                NetworkLevel.SUBSTATION, "SS-MTH-01", "Mathura 33/11 kV substation", start, null);

        // (1) creating the feeder, with substation as its parent
        NetworkNode feeder = new NetworkNode(utility, substation, NetworkDomain.ELECTRICITY, NetworkLevel.FEEDER, "FDR-MTH-04",
                "Feeder kV", start, null);
        // (2) creating transformer DT-0114, with the feeder as its parent
        NetworkNode transformer = new NetworkNode(utility, feeder, NetworkDomain.ELECTRICITY, NetworkLevel.DISTRIBUTION_TRANSFORMER, "DT-0114",
                "Nagla Chhitar transformer", start, null);
        repository.save(substation);
        repository.save(feeder);
        repository.save(transformer);


        entityManager.flush();
//        entityManager.clear();

        NetworkNode loaded = repository.findById(transformer.getId()).orElseThrow();

        assertThat(loaded.getCode()).isEqualTo("DT-0114");
        assertThat(loaded.getParent().getCode()).isEqualTo("FDR-MTH-04");
        assertThat(loaded.getParent().getParent().getCode()).isEqualTo("SS-MTH-01");
        assertThat(loaded.getParent().getParent().getParent()).isNull();
    }

    @Test
    void findsTheChildrenOfAFeeder() {

        NetworkNode substation = new NetworkNode(utility, null, NetworkDomain.ELECTRICITY,
                NetworkLevel.SUBSTATION, "SS-MTH-01", "Mathura 33/11 kV substation", start, null);

        // (1) creating the feeder, with substation as its parent
        NetworkNode feeder = new NetworkNode(utility, substation, NetworkDomain.ELECTRICITY, NetworkLevel.FEEDER, "FDR-MTH-04",
                "Feeder kV", start, null);
        // (2) creating transformer DT-0114, with the feeder as its parent
        NetworkNode transformer = new NetworkNode(utility, feeder, NetworkDomain.ELECTRICITY, NetworkLevel.DISTRIBUTION_TRANSFORMER, "DT-0114",
                "Nagla Chhitar transformer", start, null);
        // (2) creating transformer DT-0115, with the feeder as its parent
        NetworkNode transformer1 = new NetworkNode(utility, feeder, NetworkDomain.ELECTRICITY, NetworkLevel.DISTRIBUTION_TRANSFORMER, "DT-0115",
                "Nagla Chhitar transformer2", start, null);
        repository.save(substation);
        repository.save(feeder);
        repository.save(transformer);
        repository.save(transformer1);


        entityManager.flush();
        entityManager.clear();

        NetworkNode freshFeeder = repository.findById(feeder.getId()).orElseThrow();
        List<NetworkNode> loaded = repository.findByParent(freshFeeder);

//        assertThat(loaded).hasSize(2);
        assertThat(loaded).extracting(NetworkNode::getCode).containsExactlyInAnyOrder("DT-0115", "DT-0114");
    }
}