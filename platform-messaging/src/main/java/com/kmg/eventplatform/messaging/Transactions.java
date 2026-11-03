package com.kmg.eventplatform.messaging;

import org.springframework.transaction.support.TransactionSynchronizationManager;

final class Transactions {

    private Transactions() {
    }

    /**
     * The point of the outbox and of the processed messages: written with the business change, or not at all.
     */
    static void requireActive(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " must run in the transaction of the business change");
        }
    }
}
