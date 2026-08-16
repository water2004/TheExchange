package org.edtp.theexchange.network.protocol.messages;

public record TransactionQuery(String transactionId, String intentHash) {}
