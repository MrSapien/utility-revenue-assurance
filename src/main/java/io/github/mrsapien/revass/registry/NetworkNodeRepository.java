package io.github.mrsapien.revass.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NetworkNodeRepository extends JpaRepository<NetworkNode, UUID> {
    List<NetworkNode> findByParent(NetworkNode parent);
}