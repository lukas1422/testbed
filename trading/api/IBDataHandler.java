package api;

import client.Contract;
import client.TickType;
import handler.HistoricalHandler;
import handler.LiveHandler;
import historical.Request;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static Trader.Allstatic.globalRequestMap;
import static Trader.TradingUtility.getESTDateTimeNow;
import static utility.Utility.ibContractToSymbol;
import static utility.Utility.pr;

public class IBDataHandler {

    public static void tickPrice(int reqId, int tickType, double price) {
//        pr("tickPrice", reqId, tickType, price);
        if (globalRequestMap.containsKey(reqId)) {
            Request r = globalRequestMap.get(reqId);
            LiveHandler lh = (LiveHandler) globalRequestMap.get(reqId).getHandler();
            try {
                lh.handlePrice(TickType.get(tickType), r.getContract(), price,
                        getESTDateTimeNow().truncatedTo(ChronoUnit.MILLIS));
            } catch (Exception ex) {
                pr(" handling price has issues ");
                ex.printStackTrace();
            }
        }
    }

    public static void tickSize(int reqId, int tickType, long size) {
        if (globalRequestMap.containsKey(reqId)) {
            Request r = globalRequestMap.get(reqId);
            LiveHandler lh = (LiveHandler) r.getHandler();
            lh.handleVol(TickType.get(tickType), ibContractToSymbol(r.getContract()), size,
                    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES));
        }
    }

    public static void tickGeneric(int reqId, int tickType, double value) {
        if (globalRequestMap.containsKey(reqId)) {
            Request r = globalRequestMap.get(reqId);
            LiveHandler lh = (LiveHandler) r.getHandler();
            lh.handleGeneric(TickType.get(tickType), ibContractToSymbol(r.getContract()), value,
                    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES));
        }
    }

    public static void tickString(int reqId, int tickType, String value) {
        if (globalRequestMap.containsKey(reqId)) {
            Request r = globalRequestMap.get(reqId);
            LiveHandler lh = (LiveHandler) r.getHandler();
            lh.handleString(TickType.get(tickType), ibContractToSymbol(r.getContract()), value,
                    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES));
        }
    }

    public static void historicalData(int reqId, String date, double open, double high, double low,
                                      double close, long volume, int count, double wap) {
        if (globalRequestMap.containsKey(reqId)) {
            Request r = globalRequestMap.get(reqId);
            String symb = utility.Utility.ibContractToSymbol(r.getContract());

            if (r.getCustomFunctionNeeded()) {
                r.getDataConsumer().apply(r.getContract(), date, open, high, low, close, volume);
            } else {
                HistoricalHandler hh = (HistoricalHandler) r.getHandler();
                Contract c = r.getContract();
                if (!date.startsWith("finished")) {
                    try {
                        hh.handleHist(c, date, open, high, low, close);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                } else if (date.toUpperCase().startsWith("ERROR")) {
                    hh.actionUponFinish(c);
                    throw new IllegalStateException(" error found ");
                } else {
                    hh.actionUponFinish(c);
                }
            }
        }
    }

    public static void historicalDataEnd(int reqId) {
        if (globalRequestMap.containsKey(reqId)) {
            Request req = globalRequestMap.get(reqId);
            Contract c = req.getContract();
            String symb = ibContractToSymbol(req.getContract());
            if (req.getPerformActionOnFinish()) {
//                pr(reqId, symb, "historical Data End: action on finish");
                req.runRunnable();
            }
//            else {
//                HistoricalHandler hh = (HistoricalHandler) req.getHandler();
//                hh.actionUponFinish(c);
//            }
        }
    }

}