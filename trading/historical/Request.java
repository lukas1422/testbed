package historical;

import client.Contract;
import handler.GeneralHandler;
import handler.HistDataConsumer;

import java.util.function.Consumer;

import static utility.Utility.ibContractToSymbol;
import static utility.Utility.pr;

public class Request {

    private Contract contract;
    private GeneralHandler handler;
    private HistDataConsumer<Contract, String, Double, Long> dataConsumer;
    private Runnable actionUponFinish;
    private boolean customHandlingNeeded;
    private boolean performActionOnFinish;

    public Request(Contract ct, GeneralHandler h) {
        contract = ct;
        handler = h;
        dataConsumer = null;
        customHandlingNeeded = false;
        performActionOnFinish = false;
    }

    public Request(Contract ct, HistDataConsumer<Contract, String, Double, Long> dc) {
        contract = ct;
        handler = null;
        dataConsumer = dc;
        customHandlingNeeded = true;
        performActionOnFinish = false;
    }

    public Request(Contract ct, HistDataConsumer<Contract, String, Double, Long> dc, Runnable r) {
        contract = ct;
        handler = null;
        dataConsumer = dc;
        actionUponFinish = r;
        customHandlingNeeded = true;
        performActionOnFinish = true;
    }

    public Contract getContract() {
        return contract;
    }

    public GeneralHandler getHandler() {
        return handler;
    }

    public HistDataConsumer<Contract, String, Double, Long> getDataConsumer() {
        return (customHandlingNeeded) ? dataConsumer : null;
    }

    public boolean getCustomFunctionNeeded() {
        return customHandlingNeeded;
    }

    public boolean getPerformActionOnFinish() {
        return performActionOnFinish;
    }

    public void runRunnable() {
//        pr("running runnable in request");
        actionUponFinish.run();
    }

    @Override
    public String toString() {
        return ibContractToSymbol(contract) + " " + handler.toString()
                + " custom handling needed " + getCustomFunctionNeeded();
    }
}
