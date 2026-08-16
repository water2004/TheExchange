package org.edtp.theexchange.network.protocol.messages;

public record TransactionSettled(String transactionId, String resultHash) {}
