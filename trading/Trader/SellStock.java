package Trader;

import api.OrderAugmented;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static Trader.Allstatic.*;
import static Trader.TradingUtility.*;
import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.INVENTORY_CUTTER;
import static java.lang.Math.round;
import static java.util.stream.Collectors.*;
import static utility.Utility.ibContractToSymbol;
import static utility.Utility.pr;

public class SellStock implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {

    private static final int GATEWAY_PORT = 4001;
    private static final int TWS_PORT = 7496;
    private static final int PORT_TO_USE = GATEWAY_PORT;
    private static final int MASTERID = getSessionMasterTradeID();
    private static ApiController api;
    //static Contract apwc = generateUSStockContract("apwc");
    private static volatile TreeSet<String> targets = new TreeSet<>();
    private static Map<String, Integer> symbolContractIDMap = new ConcurrentHashMap<>();
    private static Map<String, List<ExecutionAugmented>> tradeKeyExecutionMap = new ConcurrentHashMap<>();
    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>> orderSubmitted = new ConcurrentHashMap<>();
    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>>
            orderStatus = new ConcurrentHashMap<>();
    private static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>>
            openOrders = new ConcurrentSkipListMap<>();


    //private static double apwcAvgCost = Double.MAX_VALUE;
    //private static double apwcBid = 0.0;
    //private static double apwcAsk = Double.MAX_VALUE;
    //private static Decimal apwcPosition = Decimal.ZERO;

    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();
    private volatile static Map<String, Double> amountToSell = new HashMap<>();


    private static Map<String, Contract> symbolContractMap = new HashMap<>();
    private volatile static Map<String, Decimal> symbPos =
            new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile AtomicInteger tradID = new AtomicInteger(MASTERID + 100000);

    //static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);

    private SellStock() throws IOException {
        outputToGeneral("*****START***** HKT:", hkTime(), "EST:", usDateTime(), "MASTERID:", MASTERID);
        pr("mkt start time today:", TODAY930);
        pr("until mkt start time:", Duration.between(TODAY930,
                getESTDateTimeNow()).toMinutes(), "mins");

        Files.lines(Paths.get(RELATIVEPATH + "sellList")).map(l -> l.split(" "))
                .forEach(a -> {
//                    pr("whole line", a);
                    pr("a[0]", a[0], a[1]);
//                    String stockName = a[0].equalsIgnoreCase("BRK") ? "BRK B" : a[0];
                    registerContract(generateUSStockContract(a[0]));
                    amountToSell.put(a[0], Double.valueOf(a[1]));
                });
        pr("amount to sell ", amountToSell);

    }

    private void connectAndReqPos() {
        api = new ApiController(new DefaultConnectionHandler(),
                new Utility.DefaultLogger(), new Utility.DefaultLogger());
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


    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        outputToSymbol(symb, "*******************************************");
        outputToSymbol(symb, "*STARTS*", usDateTime());
        symbolContractMap.put(symb, ct);
        targets.add(symb);

        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
        orderStatus.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
    }


    @Override
    public void position(String account, Contract contract, Decimal position, double avgCost) {
        String s = ibContractToSymbol(contract);

        pr("position", ibContractToSymbol(contract), position, avgCost);

        if (!contract.symbol().equals("USD") && targets.contains(s)) {
            symbPos.put(s, position);
            costMap.put(s, avgCost);
            outputToSymbol(s, "updating position:", usDateTime(),
                    "position:" + position, "cost:" + round2(avgCost));
        }
    }

    @Override
    public void positionEnd() {
        pr(usDateTime(), "position end");
        targets.forEach(s -> {
            if (!symbPos.containsKey(s)) {
                symbPos.put(s, Decimal.ZERO);
            }

            outputToSymbol(s, "POS:" + symbPos.get(s).longValue(),
                    "COST:" + round1(costMap.getOrDefault(s, 0.0)),
                    "DELT:" + round(symbPos.get(s).longValue() *
                            costMap.getOrDefault(s, 0.0) / 1000.0) + "k");

            api.reqContractDetails(symbolContractMap.get(s), list -> list.forEach(a ->
                    symbolContractIDMap.put(s, a.contract().conid())));

            es.schedule(() -> {
                pr("Position end: requesting live:", s);
                req1ContractLive(api, symbolContractMap.get(s), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        String s = ibContractToSymbol(contract);
        if (s.equalsIgnoreCase("USD")) {
            return;
        }

        outputToSymbol(s, usDateTime(), "*tradeReport* time:",
                executionToUSTime(execution.time()), execution.side(),
                "execPx:" + execution.price(), "shares:" + execution.shares(), "avgPx:" + execution.avgPrice());

        if (!tradeKeyExecutionMap.containsKey(tradeKey)) {
            tradeKeyExecutionMap.put(tradeKey, new LinkedList<>());
        }
        tradeKeyExecutionMap.get(tradeKey).add(new ExecutionAugmented(s, execution));

    }

    @Override
    public void tradeReportEnd() {
        outputToGeneral(usDateTime(), "*TradeReportEnd*: all executions:", tradeKeyExecutionMap);
        tradeKeyExecutionMap.values().stream().flatMap(Collection::stream)
                .collect(groupingBy(ExecutionAugmented::getSymbol,
                        mapping(ExecutionAugmented::getExec, toList())))
                .forEach((key, value) -> outputToSymbol(key, "listOfExecs",
                        value.stream().sorted(Comparator.comparingDouble(Execution::orderId))
                                .collect(Collectors.toList())));
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
//                apwcBid = price;
                bidMap.put(symb, price);
                break;
            case ASK:
//                apwcAsk = price;
                askMap.put(symb, price);
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

        if (px > costMap.get(s)) {
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
        double cost = costMap.get(s);
        double offerPrice = r(Math.max(askMap.get(s), px));
        Order o = placeOfferLimitTIF(id, offerPrice, Decimal.get(10L), DAY);
        orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
        orderStatus.get(s).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
        outputToSymbol(s, "ordID:" + o.orderId(), "tradID:" + id, o.action(), "px:" + offerPrice,
                "q:" + o.totalQuantity().longValue(), "cost:" + round2(cost));

        outputToSymbol(s, orderSubmitted.get(s).get(o.orderId()),
                "reqMargin:" + round5(tgtProfitMargin(s)),
                "tgtSellPx:" + round2(cost * tgtProfitMargin(s)),
                "askPx:" + askMap.get(s));
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

        SellStock test1 = new SellStock();
        test1.connectAndReqPos();

    }
}
