package Trader;

import client.Execution;

public class ExecutionAugmented {

    private Execution execution;
    private String symbol;

    public ExecutionAugmented(String s, Execution e) {
        symbol = s;
        execution = e;
    }

    public Execution getExec() {
        return execution;
    }

    public String getSymbol() {
        return symbol;
    }

}
