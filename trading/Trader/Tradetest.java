package Trader;

import api.OrderAugmented;
import client.*;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import utility.Utility;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static Trader.Allstatic.*;
import static Trader.Allstatic.openOrders;
import static Trader.TradingUtility.*;
import static Trader.TradingUtility.getESTLocalDateTimeNow;
import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.INVENTORY_ADDER;
import static utility.Utility.*;
import static utility.Utility.pr;

public class Tradetest implements LiveHandler, ApiController.ILiveOrderHandler,
        ApiController.ITradeReportHandler {
    private static ApiController apiController;
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();

    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = GATEWAY_PORT;

    //    static Contract tencent = generateHKStockContract("700");
    static Contract wmt = generateUSStockContract("WMT");

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
//            pr(" using port 4001 GATEWAY");
//            ap.connect("127.0.0.1", TWS_PORT, 5, "");
            ap.connect("127.0.0.1", PORT_TO_USE, 6, "");
            l.countDown();
            pr(" Latch counted down 4001 " + getESTLocalDateTimeNow().format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }


        try {
            l.await();
        } catch (InterruptedException e) {
            outputToGeneral("error in connection:", e);
        }

//        targetStockList.forEach(symb -> {
//            es.schedule(() -> {
//                pr("Position end: requesting live:", symb);
//                req1ContractLive(apiController, symbolContractMap.get(symb), this, false);
//            }, 10L, TimeUnit.SECONDS);
//        });
        es.schedule(() -> {
            //apiController.reqLiveOrders(this);
            apiController.reqExecutions(new ExecutionFilter(),this);
        }, 10L, TimeUnit.SECONDS);


    }

    private static void registerContractAll(Contract... cts) {
        Arrays.stream(cts).forEach(Tradetest::registerContract);
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        symbolContractMap.put(symb, ct);
        targetStockList.add(symb);
        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
        orderStatusMap.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr("last::", symb, price, t.format(simpleHrMinSec));
                break;
            case BID:
                bidMap.put(symb, price);
                break;
            case ASK:
                askMap.put(symb, price);
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

    private static void testTrade(Contract ct, double price, LocalDateTime t, Decimal sizeToBuy) {
        String symb = ibContractToSymbol(ct);
        int id = 200;
        pr("trade ID is ", id);
        double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
        Order o = placeBidLimitTIF(id, bidPrice, sizeToBuy, DAY);
        placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, o.orderId()));
        pr(symb, "orderID:", o.orderId(), "tradeID:", id, "action:", o.action(),
                "px:", bidPrice, "size:", sizeToBuy);
    }


    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        pr("openOrder call back ", ibContractToSymbol(contract), order, orderState);
    }

    @Override
    public void openOrderEnd() {
        pr("open order end");
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {
        pr(usTime(), "openOrder orderStatus callback:", "orderId:", orderId, "OrderStatus:",
                status, "filled:", filled, "remaining:", remaining, "fillPrice", avgFillPrice,
                "lastFillPrice:", lastFillPrice, "clientID:", clientId);
    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        if (errorCode == 2157) {
            pr("ignoring 2157", "orderID:", orderId, "msg:", errorMsg);
            return;
        }
        outputToGeneral("openOrder ERROR:", usTime(), "orderId:",
                orderId, " errorCode:", errorCode, " msg:", errorMsg);
    }

    //open orders end **********************
    public static void main(String[] args) {
        Tradetest test1 = new Tradetest();
        test1.connectAndReqPos();
        //pr("trade key exec map", getSessionMasterTradeID());
//        testTrade(wmt, 50, getESTLocalDateTimeNow(), Decimal.get(1));
//        es.schedule(() -> testTrade(wmt, 100, getESTLocalDateTimeNow(), Decimal.get(1)), 10L, TimeUnit.SECONDS);
//        es.schedule(() -> apiController.client().reqIds(-1), 3L, TimeUnit.SECONDS);
    }

    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        pr("tradekey",tradeKey);

        String symb = ibContractToSymbol(contract);

        if (symb.startsWith("hk")) {
            return;
        }

        pr(symb, usDateTime(), "tradeReport time:",
                executionToUSTime(execution.time()), execution.side(), "exec price:",
                execution.price(), "shares:", execution.shares(), "avgExecPrice:", execution.avgPrice());
    }

    @Override
    public void tradeReportEnd() {
        pr("trade report end");
    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {
        pr("tradeKey, commission", tradeKey, commissionReport.commission());
    }
}
