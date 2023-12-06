package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static Trader.Allstatic.*;
import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.OrderStatus.Filled;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static Trader.TradingUtility.*;
import static java.time.temporal.ChronoUnit.MINUTES;
import static utility.Utility.*;

public class ProfitTargetTrader implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {
    private static ApiController apiController;
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();
    static volatile AtomicInteger tradeID = new AtomicInteger(getNewTradeID());


    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = TWS_PORT;

    public static Map<String, Double> averageDailyRange = new HashMap<>();

    //    Contract tencent = generateHKStockContract("700");
    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");
    Contract ul = generateUSStockContract("UL");
    Contract mcd = generateUSStockContract("MCD");
    Contract spy = generateUSStockContract("SPY");
    Contract ko = generateUSStockContract("KO");

    //avoid too many requests at once, only 50 requests allowed at one time.
    //private static Semaphore histSemaphore = new Semaphore(45);
    //Trade
    //    private static volatile Map<String, AtomicBoolean> addedMap = new ConcurrentHashMap<>();
    //    private static volatile Map<String, AtomicBoolean> liquidatedMap = new ConcurrentHashMap<>();
    //    private static volatile Map<String, AtomicBoolean> tradedMap = new ConcurrentHashMap<>();
    //    public static final LocalDateTime TODAY_MARKET_START_TIME =
    //            LocalDateTime.of(LocalDateTime.now().toLocalDate()., LocalTime.of(9, 30));

    //            LocalDateTime.of(ZonedDateTime.now().withZoneSameInstant(ZoneId.off("America/New_York")).toLocalDate(), ltof(9, 30));

    private ProfitTargetTrader() {
        outputToGeneral("*****START***** HK TIME:", hkTime(), "EST:", usTime());
        pr("market start time today ", TRADING_START_TIME);
        pr("until market start time", Duration.between(TRADING_START_TIME,
                getESTLocalDateTimeNow()).toMinutes(), "minutes");

        registerContractAll(spy, wmt, ul, pg, mcd, ko);
//        registerContract(spy);
//        registerContract(wmt);
//        registerContract(ul);
//        registerContract(pg);
//        registerContract(mcd);
//        registerContract(ko);
    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new DefaultLogger(), new DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);

        try {
//            pr(" using port 4001 GATEWAY");
//            ap.connect("127.0.0.1", TWS_PORT, 5, "");
            ap.connect("127.0.0.1", PORT_TO_USE, 5, "");
            l.countDown();
            pr(" Latch counted down 4001 " + getESTLocalDateTimeNow().format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

//        if (!connectionStatus) {
////            pr(" using port 7496");
//            ap.connect("127.0.0.1", 7496, 5, "");
//            l.countDown();
//            pr(" Latch counted down 7496 TWS" + getESTLocalTimeNow().format(f1));
//        }

        try {
            l.await();
            pr("connected");
        } catch (InterruptedException e) {
            outputToError("error in connection:", e);
        }

        pr(" Time after latch released " + usTime());

        Executors.newScheduledThreadPool(10).schedule(() -> {
            targetStockList.forEach(symb -> {
                pr("request hist day data: target stock symb ", symb);
                Contract c = symbolContractMap.get(symb);
                if (!threeDayData.containsKey(symb)) {
                    threeDayData.put(symb, new ConcurrentSkipListMap<>());
                }

                pr("requesting hist day data", symb);
                CompletableFuture.runAsync(() -> reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        histCompatibleCt(c), Allstatic::todaySoFar, 3, Types.BarSize._1_min));

                CompletableFuture.runAsync(() -> reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        histCompatibleCt(c), Allstatic::ytdOpen, () -> computeHistoricalData(symb)
                        , Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day));
            });
            apiController.reqPositions(this);
            apiController.reqLiveOrders(this);
        }, 2, TimeUnit.SECONDS);
        pr("req executions ");
        apiController.reqExecutions(new ExecutionFilter(), this);
        outputToGeneral("cancelling all orders on start up");
        apiController.cancelAllOrders();
    }

    static void computeHistoricalData(String s) {
        if (ytdDayData.containsKey(s) && !ytdDayData.get(s).isEmpty()) {
            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
                    .average().orElse(0.0);
            pr("average range:", s, rng);
            averageDailyRange.put(s, rng);

            if (ytdDayData.get(s).firstKey().isBefore(getYearBeginMinus1Day())) {
                double lastYearClose = ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                lastYearCloseMap.put(s, lastYearClose);
                pr("last year close for", s, ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()),
                        "ret on year", ytdDayData.get(s).lastEntry().getValue().getClose() / lastYearClose - 1);
            }
        } else {
            pr("no historical data to compute ", s);
        }
    }

    static void computeRange() {
        targetStockList.forEach(s -> {
            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
                    .average().orElse(0.0);
            pr("average range:", s, rng);
            averageDailyRange.put(s, rng);
        });
    }

    private static void registerContractAll(Contract... cts) {
        Arrays.stream(cts).forEach(ProfitTargetTrader::registerContract);
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        symbolContractMap.put(symb, ct);
        targetStockList.add(symb);
        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
        orderStatusMap.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
        if (!ytdDayData.containsKey(symb)) {
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    static boolean noBlockingOrders(String symb) {
        if (!orderStatusMap.get(symb).isEmpty()) {
            pr(symb, "no blocking orders check:", orderStatusMap.get(symb));
        }

        return orderStatusMap.get(symb).isEmpty() ||
                orderStatusMap.get(symb).values().stream().allMatch(OrderStatus::isFinished);
    }

    static double priceDividedByCost(double price, String symb) {
        if (costMap.containsKey(symb) && costMap.get(symb) != 0.0) {
            return price / costMap.get(symb);
        }
        return 1;
    }

    static boolean checkDeltaImpact(String symb, double price) {
        pr(symb, "check delta impact", "aggDelta<Limit", aggregateDelta < DELTA_LIMIT, "Current+Inc<Limit Each"
                , symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
                        getSizeFromPrice(price).longValue() * price < DELTA_LIMIT_EACH_STOCK);

        return aggregateDelta < DELTA_LIMIT && (symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
                getSizeFromPrice(price).longValue() * price < DELTA_LIMIT_EACH_STOCK);
    }

    static void tryToTrade(Contract ct, double price, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
//            pr("not trading time");
            return;
        }

        String symb = ibContractToSymbol(ct);
        if (!noBlockingOrders(symb)) {
            outputToSymbol(symb, t.format(simpleHrMinSec),
                    "order blocked:", symb, openOrders.get(symb).values(),
                    "**statusMap:", orderStatusMap);
            return;
        }

        if (!ct.currency().equalsIgnoreCase("USD")) {
            pr("non USD stock not allowed");
            return;
        }

        if (!threeDayPctMap.containsKey(symb) || !oneDayPctMap.containsKey(symb)) {
            pr(symb, "no percentile info check:", !threeDayPctMap.containsKey(symb) ? "3day" : "",
                    !oneDayPctMap.containsKey(symb) ? "1day" : "");
            return;
        }

        double threeDayPerc = threeDayPctMap.get(symb);
        double oneDayPerc = oneDayPctMap.get(symb);

        Decimal position = symbolPosMap.get(symb);
//        pr("Check Perc", symb, "3dp:", threeDayPerc, "1dp:", oneDayPerc, "pos:", position);

        if (oneDayPerc < 15 && checkDeltaImpact(symb, price)) {
            if (position.isZero()) {
                if (threeDayPerc < 40) {
                    outputToSymbol(symb, str("****FIRST****", t.format(f)));
                    outputToSymbol(symb, "****first buying", "3dp:",
                            threeDayPerc, "1dp:", oneDayPerc);
                    inventoryAdder(ct, price, t, getSizeFromPrice(price));
                }
            } else if (position.longValue() > 0 && costMap.containsKey(symb)) {
                if (priceDividedByCost(price, symb) < getRequiredRefillPoint(symb)
                        && threeDayPerc < 40) {
                    outputToSymbol(symb, "****REFILL****", t.format(f));
                    outputToSymbol(symb, "buyMore:",
                            "3dp:", threeDayPerc, "1dp:", oneDayPerc,
                            "p/c:", priceDividedByCost(price, symb), "refillPt"
                            , getRequiredRefillPoint(symb), "rng:", averageDailyRange.getOrDefault(symb, 0.0));
                    inventoryAdder(ct, price, t, Decimal.get(5));
                }
            }
        } else if (oneDayPerc > 80 && position.longValue() > 0) {
            double priceOverCost = priceDividedByCost(price, symb);
            pr("priceOverCost", symb, priceDividedByCost(price, symb));
            if (priceOverCost > getRequiredProfitMargin(symb)) {
                outputToSymbol(symb, "****CUT****", t.format(f1));
                outputToSymbol(symb, "Sell 1dP%:", oneDayPerc, "3dp:", threeDayPerc,
                        "priceOverCost:", priceOverCost,
                        "requiredMargin:", getRequiredProfitMargin(symb), "avgRng:",
                        averageDailyRange.getOrDefault(symb, 0.0));
                inventoryCutter(ct, price, t);
            }
        }
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr("last::", symb, price, t.format(simpleHrMinSec));
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);
                latestPriceTimeMap.put(symb, getESTLocalTimeNow());

                if (threeDayData.get(symb).containsKey(t.truncatedTo(MINUTES))) {
                    threeDayData.get(symb).get(t.truncatedTo(MINUTES)).add(price);
                } else {
                    threeDayData.get(symb).put(t.truncatedTo(MINUTES), new SimpleBar(price));
                }

                if (symbolPosMap.containsKey(symb)) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }
                tryToTrade(ct, price, t);
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
    }

    @Override
    public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {
    }

    @Override
    public void handleString(TickType tt, String symbol, String str, LocalDateTime t) {
    }
    //livedata end


    //position start
    @Override
    public void position(String account, Contract contract, Decimal position, double avgCost) {
        String symb = ibContractToSymbol(contract);

        if (!contract.symbol().equals("USD") && targetStockList.contains(symb)) {
            symbolPosMap.put(symb, position);
            costMap.put(symb, avgCost);
            outputToSymbol(symb, "updating position:", usTime(), "position:", position, "cost:", avgCost);
        }
    }

    @Override
    public void positionEnd() {
        pr(usTime(), "position end");
        targetStockList.forEach(symb -> {
            if (!symbolPosMap.containsKey(symb)) {
//                outputToSymbol(symb, "no position");
                symbolPosMap.put(symb, Decimal.ZERO);
            }

            outputToSymbol(symb, "POS COST", symbolPosMap.get(symb).longValue(), costMap.getOrDefault(symb, 0.0));

            apiController.reqContractDetails(symbolContractMap.get(symb), list -> list.forEach(a ->
                    symbolConIDMap.put(symb, a.contract().conid())));

            es.schedule(() -> {
                pr("Position end: requesting live:", symb);
                req1ContractLive(apiController, symbolContractMap.get(symb), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    static void periodicCompute() {
        targetStockList.forEach(symb -> {
            if (symbolPosMap.containsKey(symb)) {
                if (latestPriceMap.containsKey(symb) && costMap.getOrDefault(symb, 0.0) != 0.0) {
                    pr(symb, "position:", symbolPosMap.get(symb),
                            "price", latestPriceMap.get(symb),
                            "cost:", r(costMap.get(symb)), "p/c-1",
                            r(100 * (latestPriceMap.get(symb) / costMap.get(symb) - 1)), "%");
                }
            }
        });

        targetStockList.forEach(s -> {
            double rng = ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30)).values().stream()
                    .mapToDouble(SimpleBar::getHLRange).average().orElse(0.0);
//            pr("average range:", s, round5Digits(rng), "firstkey:",
//                    ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30)).firstKey(),
//                    "lastkey:", ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30))
//                            .lastKey(), "size:", ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30)).size());
            averageDailyRange.put(s, rng);
//            pr("refill point:", round5Digits(getRequiredRefillPoint(s)));
        });

        targetStockList.forEach(symb -> {
            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {
                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME));

                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                pr("compute:", symb, usTime(), "*3dP%:", threeDayPercentile,
                        "*1dP%:", oneDayPercentile, "*stats1d:",
                        printStats(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
            }

        });

        aggregateDelta = targetStockList.stream().mapToDouble(s ->
                symbolPosMap.getOrDefault(s, Decimal.ZERO).
                        longValue() * latestPriceMap.getOrDefault(s, 0.0)).sum();

        targetStockList.forEach((s) ->
                symbolDeltaMap.put(s, (double) Math.round(symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue() * latestPriceMap
                        .getOrDefault(s, 0.0))));

        pr("aggregate Delta", r(aggregateDelta), "each delta", symbolDeltaMap);

        openOrders.forEach((k, v) -> v.forEach((k1, v1) -> {
            if (orderStatusMap.get(k).get(k1).isFinished()) {
                outputToSymbol(k, "in compute: removing finished orders", k, "orderID:", k1);
                v.remove(k1);
            }
        }));
    }

    private static void inventoryAdder(Contract ct, double price, LocalDateTime t, Decimal sizeToBuy) {
        String symb = ibContractToSymbol(ct);

        if (symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) + sizeToBuy.longValue() * price
                > DELTA_LIMIT_EACH_STOCK) {
            outputToSymbol(symb, usTime(), "buying exceeds limit", "current delta:",
                    symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE),
                    "proposed delta inc:", sizeToBuy.longValue() * price);
            return;
        }
        int id = tradeID.incrementAndGet();
        double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
        Order o = placeBidLimitTIF(id, bidPrice, sizeToBuy, DAY);
        orderSubmitted.get(symb).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_ADDER));
        orderStatusMap.get(symb).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, o.orderId()));
        outputToSymbol(symb, "orderID:", o.orderId(), "tradeID:", id, "action:", o.action(),
                "px:", bidPrice, "size:", sizeToBuy, orderSubmitted.get(symb).get(o.orderId()));
    }

    private static void inventoryCutter(Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symb);

        int id = tradeID.incrementAndGet();
        double cost = costMap.getOrDefault(symb, Double.MAX_VALUE);
        double offerPrice = r(Math.max(askMap.getOrDefault(symb, price),
                costMap.getOrDefault(symb, Double.MAX_VALUE) * getRequiredProfitMargin(symb)));

        Order o = placeOfferLimitTIF(id, offerPrice, pos, DAY);
        orderSubmitted.get(symb).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
        orderStatusMap.get(symb).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, o.orderId()));
        outputToSymbol(symb, "orderID:", o.orderId(), "tradeID:", id,
                o.action(), "px:", offerPrice, "qty:", pos, "costBasis:", cost,
                orderSubmitted.get(symb).get(o.orderId()));
    }

    //request realized pnl
    //Execution details *****************
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        String symb = ibContractToSymbol(contract);

        if (symb.startsWith("hk")) {
            return;
        }

        if (!tradeKeyExecutionMap.containsKey(tradeKey)) {
            tradeKeyExecutionMap.put(tradeKey, new LinkedList<>());
        }

        tradeKeyExecutionMap.get(tradeKey).add(new ExecutionAugmented(symb, execution));

        outputToSymbol(symb, usTime(), "tradeReport time, side, price, shares, avgPrice:",
                executionToUSTime(execution.time()), execution.side(),
                execution.price(), execution.shares(), execution.avgPrice());
    }

    @Override
    public void tradeReportEnd() {
        outputToGeneral(usTime(), "TradeReportEnd: all executions:");
        tradeKeyExecutionMap.values().stream().flatMap(Collection::stream)
                .collect(Collectors.groupingBy(ExecutionAugmented::getSymbol,
                        Collectors.mapping(ExecutionAugmented::getExec, Collectors.toList())))
                .forEach((key, value) -> outputToSymbol(key, "list of executions", value));
    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {
        if (!tradeKeyExecutionMap.containsKey(tradeKey) || tradeKeyExecutionMap.get(tradeKey).isEmpty()) {
            return;
        }

        String symb = tradeKeyExecutionMap.get(tradeKey).get(0).getSymbol();

        if (orderSubmitted.containsKey(symb) && !orderSubmitted.get(symb).isEmpty()) {
            orderSubmitted.get(symb).entrySet().stream().filter(e1 -> e1.getValue().getOrder().orderId()
                            == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId())
                    .forEach(e2 -> outputToSymbol(symb, "1.commission report",
                            "orderID:", e2.getKey(), "commission:", commissionReport.commission(),
                            e2.getValue().getOrder().action() == Types.Action.SELL ? str("realized pnl:", e2.getKey(),
                                    commissionReport.realizedPNL()) : "NO PNL"));

            orderSubmitted.get(symb).forEach((key1, value1) -> {
                if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId()) {
                    outputToSymbol(symb, "2.commission report", "orderID:", value1.getOrder().orderId(), "commission:",
                            commissionReport.commission(), value1.getOrder().action() == Types.Action.SELL ?
                                    str("realized pnl:", commissionReport.realizedPNL()) : "no pnl");
                }
            });
        }
    }

    //Execution end*********************************
    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        pr("openOrder call back");
        String symb = ibContractToSymbol(contract);
        outputToSymbol(symb, usTime(), "openOrder callback:", order, "orderState status:", orderState.status());

        orderStatusMap.get(symb).put(order.orderId(), orderState.status());

        if (orderState.status() == Filled) {
            outputToFills(symb, "filled", order);
            outputToSymbol(symb, "filled", order);
        }

        if (orderState.status().isFinished()) {
            outputToSymbol(symb, "openOrder callback:removing order", order, "status:", orderState.status());
            if (openOrders.get(symb).containsKey(order.orderId())) {
                openOrders.get(symb).remove(order.orderId());
            }
            outputToSymbol(symb, usTime(), "openOrder callback:after removal." +
                    "open orders:", symb, openOrders.get(symb));

        } else { //order is not finished
            openOrders.get(symb).put(order.orderId(), order);
        }
    }

    @Override
    public void openOrderEnd() {
        outputToGeneral("openOrderEnd: print all openOrders", openOrders,
                "***orderStatus:", orderStatusMap);
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {

        outputToGeneral(usTime(), "openOrder orderStatus callback:", "orderId:", orderId, "OrderStatus:",
                status, "filled:", filled, "remaining:", remaining, "fillPrice", avgFillPrice, "lastFillPrice:", lastFillPrice);

        if (status.isFinished()) {
            openOrders.forEach((k, v) -> {
                if (v.containsKey(orderId)) {
                    outputToSymbol(k, usTime(), "openOrder orderStatus " +
                            "Callback: deleting filled from open orders", openOrders);
                    outputToSymbol(k, "status:", status,
                            "removing order from openOrders. OrderID:", orderId, "order details:", v.get(orderId),
                            "remaining:", remaining);
                    v.remove(orderId);
                    outputToSymbol(k, "remaining open orders for ", k, v);
                    outputToSymbol(k, "remaining ALL open orders", openOrders);
                }
            });
        }
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
        ProfitTargetTrader test1 = new ProfitTargetTrader();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCompute, 10L, 10L, TimeUnit.SECONDS);
        es.scheduleAtFixedRate(() -> {
            targetStockList.forEach(symb -> {
                outputToSymbol(symb,
                        latestPriceTimeMap.containsKey(symb) ? str(usTime(), "last Live price feed time:",
                                latestPriceTimeMap.get(symb).format(simpleHourMinute)) : "no live feed");
                if (!orderStatusMap.get(symb).isEmpty()) {
                    outputToSymbol(symb, "periodic check:", usTime(),
                            "orderStatus", orderStatusMap.get(symb));
                }
                if (!openOrders.get(symb).isEmpty()) {
                    outputToSymbol(symb, "periodic check:", usTime(),
                            "openOrders", openOrders.get(symb));
                }
            });
        }, 10L, 600L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> outputToGeneral("*****Ending*****", usTime())));
    }
}
