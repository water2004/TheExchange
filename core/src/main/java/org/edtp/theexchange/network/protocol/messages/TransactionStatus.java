package org.edtp.theexchange.network.protocol.messages;

public class TransactionStatus {
    public enum State { UNKNOWN, RUNNING, DECIDED, CONFLICT }

    private String transactionId;
    private String intentHash;
    private State state;
    private MutationResultMessage result;

    public TransactionStatus() {}

    public TransactionStatus(String transactionId, String intentHash, State state,
                             MutationResultMessage result) {
        this.transactionId = transactionId;
        this.intentHash = intentHash;
        this.state = state;
        this.result = result;
    }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getIntentHash() { return intentHash; }
    public void setIntentHash(String intentHash) { this.intentHash = intentHash; }
    public State getState() { return state; }
    public void setState(State state) { this.state = state; }
    public MutationResultMessage getResult() { return result; }
    public void setResult(MutationResultMessage result) { this.result = result; }
}
