package Trader;

import client.*;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import utility.Utility;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static Trader.Allstatic.*;
import static Trader.TradingUtility.*;
import static Trader.TradingUtility.getESTDateTimeNow;
import static api.TradingConstants.*;
import static utility.Utility.*;
import static utility.Utility.pr;

public class Tradetest implements LiveHandler {
    private static ApiController apiController;
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();

    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = GATEWAY_PORT;

    //    static Contract tencent = generateHKStockContract("700");
    static Contract stockToTry = generateUSStockContract("WMT");

//
//    private Tradetest() {
////        registerContractAll(wmt);
//    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(),
                new Utility.DefaultLogger(), new Utility.DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);


        try {
            ap.connect("127.0.0.1", PORT_TO_USE, 6, "");
            l.countDown();
            pr(" Latch counted down 4001 " + getESTDateTimeNow().format(MdHmm));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        try {
            l.await();
        } catch (InterruptedException e) {
            outputToGeneral("error in connection:", e);
        }

//        CompletableFuture.runAsync(() -> reqHistDayData(apiController, 100, stockToTry,
//                (c, date, open, high, low, close, volume) ->
//                        pr("close:" + close), () -> pr(""), 2, Types.BarSize._1_min));

        es.schedule(() -> {
            pr("Position end: requesting live:", ibContractToSymbol(stockToTry));
            req1ContractLive(apiController, stockToTry, this, false);
        }, 2L, TimeUnit.SECONDS);
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr("last::", symb, price, t.format(Hmmss));
                break;
            case BID:
                pr("bid::", symb, price);
//                ProfitTargetTrader.bidMap.put(symb, price);
                break;
            case ASK:
                pr("ask::", symb, price);
//                ProfitTargetTrader.askMap.put(symb, price);
                break;
        }
    }

    @Override
    public void handleVol(TickType tt, String symbol, double vol, LocalDateTime t) {
        pr("vol::", symbol, vol, t);
    }

    @Override
    public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {
    }

    @Override
    public void handleString(TickType tt, String symbol, String str, LocalDateTime t) {
    }

//    private static void testTrade(Contract ct, double price, LocalDateTime t, Decimal sizeToBuy) {
//        String symb = ibContractToSymbol(ct);
//        int id = 200;
//        pr("trade ID is ", id);
//        double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
//        Order o = placeBidLimitTIF(id, bidPrice, sizeToBuy, DAY);
//        placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, o.orderId()));
//        pr(symb, "orderID:", o.orderId(), "tradeID:", id, "action:", o.action(),
//                "px:", bidPrice, "size:", sizeToBuy);
//    }

    //open orders end **********************
    public static void main(String[] args) {
        Tradetest test1 = new Tradetest();
        test1.connectAndReqPos();
        //pr("trade key exec map", getSessionMasterTradeID());
//        testTrade(wmt, 50, getESTLocalDateTimeNow(), Decimal.get(1));
//        es.schedule(() -> testTrade(wmt, 100, getESTLocalDateTimeNow(), Decimal.get(1)), 10L, TimeUnit.SECONDS);
//        es.schedule(() -> apiController.client().reqIds(-1), 3L, TimeUnit.SECONDS);
    }

}
