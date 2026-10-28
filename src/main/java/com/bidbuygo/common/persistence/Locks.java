package com.bidbuygo.common.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** Helpers for the PESSIMISTIC_WRITE lock discipline in CLAUDE.md. */
public final class Locks {

    private Locks() {
    }

    /**
     * True if this transaction holds the row exclusively: locked with PESSIMISTIC_WRITE, or inserted/updated by this
     * transaction (Hibernate's WRITE mode, which JPA reports as PESSIMISTIC_FORCE_INCREMENT).
     */
    public static boolean isWriteLocked(EntityManager entityManager, Object entity) {
        LockModeType mode = entityManager.getLockMode(entity);
        return mode == LockModeType.PESSIMISTIC_WRITE || mode == LockModeType.PESSIMISTIC_FORCE_INCREMENT;
    }

    /**
     * Call on the result of a locking query. If the entity was already loaded (unlocked) earlier in this transaction,
     * the query hands back that instance with possibly stale state; re-read it under the lock.
     */
    public static <T> T ensureWriteLocked(EntityManager entityManager, T entity) {
        if (!isWriteLocked(entityManager, entity)) {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        }
        return entity;
    }
}
