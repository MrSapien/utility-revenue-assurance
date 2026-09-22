package io.github.mrsapien.revass.common;

public enum NetworkLevel {
    // For electricity
    SUBSTATION,
    FEEDER,
    DISTRIBUTION_TRANSFORMER,

    // For water
    SOURCE,
    ZONE,
    DMA,

    // For gas
    CITY_GATE_STATION,
    STEEL_NETWORK,
    MDPE_NETWORK
}
