package org.edtp.theexchange.network.protocol.messages;

public record TransactionClosed(String transactionId, String resultHash) {}
