package com.ecommerce.inventory.application;

import java.util.List;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Test transaction boundary: runs the callback directly and journals begin, commit or rollback. */
final class JournalingTransactions implements TransactionOperations {

    private final List<String> journal;

    JournalingTransactions(List<String> journal) {
        this.journal = journal;
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        journal.add("begin");
        TransactionStatus status = new SimpleTransactionStatus();
        try {
            T result = action.doInTransaction(status);
            journal.add("commit");
            return result;
        } catch (RuntimeException e) {
            journal.add("rollback");
            throw e;
        }
    }
}
