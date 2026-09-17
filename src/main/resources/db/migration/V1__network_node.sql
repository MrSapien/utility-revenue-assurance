CREATE TABLE network_node (
                              id          UUID PRIMARY KEY,
                              utility_id  UUID NOT NULL,
                              parent_id   UUID REFERENCES network_node(id),
                              domain      TEXT NOT NULL,
                              level       TEXT NOT NULL,
                              code        TEXT NOT NULL,
                              name        TEXT NOT NULL,
                              valid_from  DATE NOT NULL,
                              valid_to    DATE,
                              CONSTRAINT network_node_valid_range
                                  CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE INDEX idx_network_node_parent ON network_node (parent_id);

CREATE UNIQUE INDEX uq_network_node_code ON network_node (utility_id, code, valid_from);