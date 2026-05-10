package com.chenweikeng.imf.nra.simulator.vehicle;

/**
 * One entry in the user-curated vehicle catalog. {@code id} is human-readable and stable across
 * runs (e.g. {@code main_street_omnibus}); {@code match} says how to recognize an instance of this
 * vehicle from a nearby armor stand.
 */
public record Vehicle(String id, VehicleMatch match) {}
