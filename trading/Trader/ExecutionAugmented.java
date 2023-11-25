package Trader;

import client.Execution;

public class ExecutionAugmented {

    private Execution execution;
    private String symbol;

    public ExecutionAugmented(Execution e, String s) {
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
