package io.github.mrsapien.revass.registry;

import io.github.mrsapien.revass.common.NetworkDomain;
import io.github.mrsapien.revass.common.NetworkLevel;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "network_node")
public class NetworkNode {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID utilityId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private NetworkNode parent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NetworkDomain domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NetworkLevel level;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate validFrom;

    private LocalDate validTo;

    protected NetworkNode() {
    }

    public NetworkNode(UUID utilityId, NetworkNode parent, NetworkDomain domain,
                       NetworkLevel level, String code, String name,
                       LocalDate validFrom, LocalDate validTo) {
        this.id = UUID.randomUUID();
        this.utilityId = utilityId;
        this.parent = parent;
        this.domain = domain;
        this.level = level;
        this.code = code;
        this.name = name;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public UUID getId() { return id; }
    public UUID getUtilityId() { return utilityId; }
    public NetworkNode getParent() { return parent; }
    public NetworkDomain getDomain() { return domain; }
    public NetworkLevel getLevel() { return level; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
}