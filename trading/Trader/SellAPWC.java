package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import utility.Utility;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;

import static Trader.Allstatic.*;
import static Trader.TradingUtility.*;
import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.INVENTORY_CUTTER;
import static java.lang.Double.MAX_VALUE;
import static java.lang.Double.max;
import static java.lang.Math.round;
import static java.time.temporal.ChronoUnit.MINUTES;
import static utility.Utility.ibContractToSymbol;
import static utility.Utility.pr;

public class SellAPWC implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {

    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = GATEWAY_PORT;
    static final int MASTERID = getSessionMasterTradeID();
    private static ApiController api;
    static Contract apwc = generateUSStockContract("apwc");
    private static double apwcAvgCost = Double.MAX_VALUE;
    private static double apwcBid = 0.0;
    private static double apwcAsk = Double.MAX_VALUE;
    private static Decimal apwcPosition = Decimal.ZERO;

    private static volatile ConcurrentSkipListMap<Integer, OrderStatus> apwcOrderStatus =
            new ConcurrentSkipListMap<>();

    //static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);

    private SellAPWC() throws IOException {
        outputToGeneral("*****START***** HKT:", hkTime(), "EST:", usDateTime(), "MASTERID:", MASTERID);
        pr("mkt start time today:", TODAY930);
        pr("until mkt start time:", Duration.between(TODAY930,
                getESTDateTimeNow()).toMinutes(), "mins");
    }

    private void connectAndReqPos() {
        api = new ApiController(new DefaultConnectionHandler(), new Utility.DefaultLogger(), new Utility.DefaultLogger());
        CountDownLatch l = new CountDownLatch(1);

        try {
            api.connect("127.0.0.1", PORT_TO_USE, 6, "");
            l.countDown();
            pr("Latch counted down", PORT_TO_USE, getESTDateTimeNow().format(MdHmm));
        } catch (IllegalStateException ex) {
            pr("illegal state exception caught ", ex);
        }

        try {
            l.await();
        } catch (InterruptedException e) {
            outputToError("error in connection:", e);
        }
        pr("requesting position");
        api.reqPositions(this);
    }


    @Override
    public void position(String account, Contract contract, Decimal pos, double avgCost) {
        pr("position", ibContractToSymbol(contract), pos, avgCost);
        apwcAvgCost = avgCost;
        apwcPosition = pos;
    }

    @Override
    public void positionEnd() {
        pr(usDateTime(), "position end");
        es.schedule(() -> {
            pr("Position end: requesting live:");
            req1ContractLive(api, apwc, this, false);
        }, 10L, TimeUnit.SECONDS);


    }

    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {

    }

    @Override
    public void tradeReportEnd() {

    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {

    }

    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {

    }

    @Override
    public void openOrderEnd() {

    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining, double avgFillPrice, int permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {

    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {

    }

    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                tryToTrade(ct, price, t);
                break;
            case BID:
                apwcBid = price;
//                bidMap.put(symb, pric

                break;
            case ASK:
                apwcAsk = price;
//                askMap.put(symb, price);
                break;
        }

    }

    private static void tryToTrade(Contract ct, double px, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            return;
        }
        String s = ibContractToSymbol(ct);
        if (!noBlockingOrders(s)) {
            outputToSymbol(s, t.format(Hmmss), "order blocked by:" +
                    openOrders.get(s).values(), "orderStatus:" + orderStatus.get(s));
            return;
        }

        if (px > apwcAvgCost) {
            outputToSymbol(s, "****CUT**", t.format(MdHmmss));
            inventoryCutter(ct, px, t);
        }
    }

    static boolean noBlockingOrders(String s) {
        if (!orderStatus.get(s).isEmpty()) {
            pr(s, "no blocking orders check:", orderStatus.get(s));
        }
        return orderStatus.get(s).isEmpty() ||
                orderStatus.get(s).values().stream().allMatch(OrderStatus::isFinished);
    }

    private static void inventoryCutter(Contract ct, double px, LocalDateTime t) {
        String s = ibContractToSymbol(ct);
        //Decimal pos = symbPos.get(s);

        int id = tradID.incrementAndGet();
        double cost = apwcAsk;
        double offerPrice = r(Math.max(apwcAsk, px));
        Order o = placeOfferLimitTIF(id, offerPrice, Decimal.get(10L), DAY);
        orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
        orderStatus.get(s).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
        outputToSymbol(s, "ordID:" + o.orderId(), "tradID:" + id, o.action(), "px:" + offerPrice,
                "q:" + o.totalQuantity().longValue(), "cost:" + round2(cost));

        outputToSymbol(s, orderSubmitted.get(s).get(o.orderId()),
                "reqMargin:" + round5(tgtProfitMargin(s)),
                "tgtSellPx:" + round2(cost * tgtProfitMargin(s)),
                "askPx:" + apwcAsk);
        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
    }


    @Override
    public void handleVol(TickType tt, String symbol, double vol, LocalDateTime t) {

    }

    @Override
    public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {

    }

    @Override
    public void handleString(TickType tt, String symbol, String str, LocalDateTime t) {

    }

    public static void main(String[] args) throws IOException {

        SellAPWC test1 = new SellAPWC();
        test1.connectAndReqPos();

    }
}
