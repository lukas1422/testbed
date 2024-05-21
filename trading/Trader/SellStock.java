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
import static client.OrderStatus.Filled;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.SELL_STOCK;
import static java.lang.Math.round;
import static java.util.stream.Collectors.*;
import static utility.Utility.ibContractToSymbol;
import static utility.Utility.pr;

public class SellStock implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler,
        ApiController.ILiveOrderHandler {

    private static final int GATEWAY_PORT = 4001;
    private static final int TWS_PORT = 7496;
    private static final int PORT_TO_USE = GATEWAY_PORT;
    private static final int MASTERID = getSessionMasterTradeID();
    private static ApiController api;
    //static Contract apwc = generateUSStockContract("apwc");
    private static volatile TreeSet<String> targets = new TreeSet<>();
    private static Map<String, Integer> symbolContractIDMap = new ConcurrentHashMap<>();
    private static Map<String, List<ExecutionAugmented>> tradeKeyExecutionMap = new ConcurrentHashMap<>();
    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>>
            orderSubmitted = new ConcurrentHashMap<>();
    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>>
            orderStatus = new ConcurrentHashMap<>();
    private static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>>
            openOrders = new ConcurrentSkipListMap<>();


    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();
    private volatile static Map<String, Double> amountToSell = new HashMap<>();


    private static Map<String, Contract> symbolContractMap = new HashMap<>();
    private volatile static Map<String, Decimal> symbPos =
            new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile AtomicInteger tradID = new AtomicInteger(MASTERID + 100000000);

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
        pr("print all target stocks:", targets);
        pr("requesting position");

        Executors.newScheduledThreadPool(10).schedule(() -> {

            pr("print all target stocks:", targets);
            api.reqPositions(this);
            api.reqLiveOrders(this);
//            pr("req Executions");
//            api.reqExecutions(new ExecutionFilter(), this);
            //outputToGeneral(usDateTime(), "cancelling all orders on start up");
            //api.cancelAllOrders();
        }, 2, TimeUnit.SECONDS);


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


        if (!contract.symbol().equals("USD") && targets.contains(s)) {
            pr("position", ibContractToSymbol(contract), position, avgCost);
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
//            if (!symbPos.containsKey(s)) {
//                symbPos.put(s, Decimal.ZERO);
//            }

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
                "execPx:" + execution.price(),
                "shares:" + execution.shares(), "avgPx:" + execution.avgPrice());

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
        pr("commission report for sellstock", tradeKey, commissionReport.commission());
    }

    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {

        String s = ibContractToSymbol(contract);

        if (!targets.contains(s)) {
            outputToSymbol(s, "not in sell stock trader");
            return;
        }

        outputToSymbol(s, usDateTime(), "*openOrder* status:" + orderState.status(), order);
        orderStatus.get(s).put(order.orderId(), orderState.status());

        if (orderState.status() == Filled) {
            outputToFills(s, usDateTime(), "*openOrder* filled", order);
        }

        if (orderState.status().isFinished()) {
            outputToSymbol(s, usDateTime(), "*openOrder*:removing order. Status:",
                    orderState.status(), order);
            if (openOrders.get(s).containsKey(order.orderId())) {
                openOrders.get(s).remove(order.orderId());
            }
            outputToSymbol(s, usDateTime(), "*openOrder*:after removal. openOrders:", openOrders.get(s));
        } else { //order is not finished
            openOrders.get(s).put(order.orderId(), order);
        }
        if (!openOrders.get(s).isEmpty()) {
            outputToSymbol(s, usDateTime(), "*openOrder* all live orders", openOrders.get(s));
        }

    }

    @Override
    public void openOrderEnd() {
        outputToGeneral(usDateTime(), "*openOrderEnd*:print all openOrders Sell Stock", openOrders,
                "orderStatusMap:", orderStatus);
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining, double avgFillPrice,
                            int permId, int parentId, double lastFillPrice, int clientId,
                            String whyHeld, double mktCapPrice) {

        outputToGeneral(usDateTime(), "*OrderStatus*:" + status, "orderId:" + orderId,
                "filled:" + filled.longValue(), "remaining:" + remaining,
                "fillPx:" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        String s = findSymbolByID(orderId);
        if (s.equalsIgnoreCase("")) {
            outputToError("*orderStatus* orderID not found in sellstock:", orderId);
            return;
        }

        outputToSymbol(s, usDateTime(), "*OrderStatus*:" + status,
                "orderId:" + orderId, "filled:" + filled, "remaining:" + remaining,
                "fillPx:" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        if (status == Filled) {
            outputToFills(s, usDateTime(), "*OrderStatus*: filled. ordID:" + orderId);
        }

        //put status in orderstatusmap
        orderStatus.get(s).put(orderId, status);

        //removing finished orders
        if (status.isFinished()) {
            if (openOrders.get(s).containsKey(orderId)) {
                outputToSymbol(s, usDateTime(), "*OrderStatus*:" + status,
                        "deleting finished orders from openOrderMap", openOrders.get(s));
                openOrders.get(s).remove(orderId);
                outputToSymbol(s, "*OrderStatus* remaining openOrders:", openOrders.get(s));
                outputToSymbol(s, "*OrderStatus* print ALL openOrders:", openOrders);
            }
        }

    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToError("*openOrder* Error", usDateTime(), "orderId:" +
                orderId, "errorCode:" + errorCode, "msg:" + errorMsg);
    }

    private static String findSymbolByID(int id) {
        for (String k : orderStatus.keySet()) {
            if (orderStatus.get(k).containsKey(id)) {
                return k;
            }
        }
        return "";
    }

    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr(t.format(Hmmss), "last p:", symb, price);
                tryToSell(ct, price, t);
                break;
            case BID:
                bidMap.put(symb, price);
                break;
            case ASK:
                askMap.put(symb, price);
                break;
        }

    }

    private static void tryToSell(Contract ct, double px, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            return;
        }
        String s = ibContractToSymbol(ct);
        if (!noBlockingOrders(s)) {
            outputToSymbol(s, t.format(Hmmss), "order blocked by:" +
                    openOrders.get(s).values(), "orderStatus:" + orderStatus.get(s));
            return;
        }

        if (px > costMap.get(s) * 1.01) {
            outputToSymbol(s, "****CUT**", t.format(MdHmmss));
            sellStockCutter(ct, px, t);
        }
    }

    private static boolean noBlockingOrders(String s) {
        if (!orderStatus.get(s).isEmpty()) {
            pr(s, "no blocking orders check:", orderStatus.get(s));
        }
        return orderStatus.get(s).isEmpty() ||
                orderStatus.get(s).values().stream().allMatch(OrderStatus::isFinished);
    }

    private static void sellStockCutter(Contract ct, double px, LocalDateTime t) {
        String s = ibContractToSymbol(ct);

        int id = tradID.incrementAndGet();
        double cost = costMap.get(s);
        double offerPrice = r(Math.max(askMap.getOrDefault(s,px), px));
        Order o = placeOfferLimitTIF(id, offerPrice, Decimal.get(amountToSell.get(s)), DAY);
        orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, SELL_STOCK));
        orderStatus.get(s).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
        outputToSymbol(s, "ordID:" + o.orderId(), "tradID:" + id, o.action(), "px:" + offerPrice,
                "q:" + o.totalQuantity().longValue(), "cost:" + round2(cost));

        outputToSymbol(s, orderSubmitted.get(s).get(o.orderId()), "askPx:" + askMap.get(s));
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
