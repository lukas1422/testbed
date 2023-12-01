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

    @Override
    public String toString() {
        return "ExecutionAugmented{" +
                "execution=" + execution +
                ", symbol='" + symbol + '\'' +
                '}';
    }
}
