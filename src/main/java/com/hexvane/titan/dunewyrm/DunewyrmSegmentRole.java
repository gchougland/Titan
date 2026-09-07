package com.hexvane.titan.dunewyrm;

/**
 * Role of one logical piece in a Dunewyrm chain.
 *
 * <p>Only {@link #BODY} segments carry hit points. Head, jaw, tongue and tail are visual.
 */
public enum DunewyrmSegmentRole {
    HEAD,
    JAW,
    TONGUE,
    BODY,
    TAIL;

    public boolean hasHealth() {
        return this == BODY;
    }
}
