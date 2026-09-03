package com.hexvane.titan.entity;

/**
 * Attack or engage request written by the brain NPC Role (or Encounter) and consumed by
 * {@link com.hexvane.titan.system.TitanAiSystem}.
 *
 * <p>Keeps Role instruction trees as the authorable brain while the titan cluster only executes.
 */
public enum TitanIntent {
    NONE,
    WAKE,
    CHASE,
    MELEE,
    SLAM,
    POUND,
    HURL,
    PLOW,
    STOMP
}
