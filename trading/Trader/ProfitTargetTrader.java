package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import enums.MASentiment;
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
    static final int MASTER_TRADE_ID = getSessionMasterTradeID();
    static volatile AtomicInteger tradeID = new AtomicInteger(MASTER_TRADE_ID + 1);


    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = GATEWAY_PORT;

    public static Map<String, Double> averageDailyRange = new HashMap<>();

    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");
    Contract ul = generateUSStockContract("UL");
    Contract mcd = generateUSStockContract("MCD");
    Contract spy = generateUSStockContract("SPY");
    Contract ko = generateUSStockContract("KO");

    private ProfitTargetTrader() {
        outputToGeneral("*****START***** HK TIME:", hkTime(), "EST:", usTime(), "MASTER ID:", MASTER_TRADE_ID);
        pr("market start time today ", TRADING_START_TIME);
        pr("until market start time", Duration.between(TRADING_START_TIME,
                getESTLocalDateTimeNow()).toMinutes(), "minutes");
        registerContractAll(spy, wmt, ul, pg, mcd, ko);
    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new DefaultLogger(), new DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);

        try {
            ap.connect("127.0.0.1", PORT_TO_USE, 5, "");
            l.countDown();
            pr(" Latch counted down", PORT_TO_USE, getESTLocalDateTimeNow().format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        try {
            l.await();
        } catch (InterruptedException e) {
            outputToError("error in connection:", e);
        }

        Executors.newScheduledThreadPool(10).schedule(() -> {
            targetStockList.forEach(symb -> {
                Contract c = symbolContractMap.get(symb);
                if (!threeDayData.containsKey(symb)) {
                    threeDayData.put(symb, new ConcurrentSkipListMap<>());
                }

                pr("requesting hist day data", symb);
                CompletableFuture.runAsync(() -> reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::todaySoFar, 3, Types.BarSize._1_min));

                CompletableFuture.runAsync(() -> reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::ytdOpen, () -> computeHistoricalData(symb)
                        , Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day));
            });
            apiController.reqPositions(this);
            apiController.reqLiveOrders(this);
        }, 2, TimeUnit.SECONDS);
        pr("req Executions");
        apiController.reqExecutions(new ExecutionFilter(), this);
        outputToGeneral("cancelling all orders on start up");
        apiController.cancelAllOrders();
    }

    static void computeHistoricalData(String s) {
        if (ytdDayData.containsKey(s) && !ytdDayData.get(s).isEmpty()) {
            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
                    .average().orElse(0.0);
            pr("average range:", s, round5Digits(rng));
            averageDailyRange.put(s, rng);

            if (ytdDayData.get(s).firstKey().isBefore(getYearBeginMinus1Day())) {
                double lastYearClose = ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                lastYearCloseMap.put(s, lastYearClose);
                pr("last year close for", s, ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getKey(),
                        ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose(),
                        "YTD:", round5Digits(ytdDayData.get(s).lastEntry().getValue().getClose() / lastYearClose - 1));
            }
        } else {
            pr("no historical data to compute ", s);
        }
    }

//    static void computeRange() {
//        targetStockList.forEach(s -> {
//            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
//                    .average().orElse(0.0);
//            pr("average range:", s, rng);
//            averageDailyRange.put(s, rng);
//        });
//    }

    private static void registerContractAll(Contract... cts) {
        Arrays.stream(cts).forEach(ProfitTargetTrader::registerContract);
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        outputToSymbol(symb, "****************STARTS", usDateTime());
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

    static boolean noBlockingOrders(String s) {
        if (!orderStatusMap.get(s).isEmpty()) {
            pr(s, "no blocking orders check:", orderStatusMap.get(s));
        }
        return orderStatusMap.get(s).isEmpty() ||
                orderStatusMap.get(s).values().stream().allMatch(OrderStatus::isFinished);
    }

    private static double priceDividedByCost(double price, String symb) {
        if (costMap.containsKey(symb) && costMap.get(symb) != 0.0) {
            return price / costMap.get(symb);
        }
        return 1;
    }

    private static boolean checkDeltaImpact(String symb, double price) {
        double position = symbolPosMap.get(symb).longValue();
        double addition = getSizeFromPrice(price, position).longValue() * price;

        pr(symb, "check delta impact", "aggDelta+addition<Delta Limit:", aggregateDelta + addition < DELTA_LIMIT,
                "Current+Inc<Stock Limit:", symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
                        getSizeFromPrice(price, position).longValue() * price < DELTA_LIMIT_EACH_STOCK);
        return aggregateDelta + addition < DELTA_LIMIT &&
                (symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
                        addition < DELTA_LIMIT_EACH_STOCK);
    }

    static void tryToTrade(Contract ct, double price, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
//            pr("not trading time");
            return;
        }

        String symb = ibContractToSymbol(ct);
        if (!noBlockingOrders(symb)) {
            outputToSymbol(symb, t.format(simpleHrMinSec), "order blocked:", symb,
                    openOrders.get(symb).values(), "**statusMap:", orderStatusMap.get(symb));
            return;
        }

        if (!ct.currency().equalsIgnoreCase("USD")) {
            pr("non USD stock not allowed");
            return;
        }

        if (!threeDayPctMap.containsKey(symb) || !oneDayPctMap.containsKey(symb)) {
            pr(symb, "no percentile info:", !threeDayPctMap.containsKey(symb) ? "3day" : "",
                    !oneDayPctMap.containsKey(symb) ? "1day" : "");
            return;
        }

        double threeDayPerc = threeDayPctMap.get(symb);
        double oneDayPerc = oneDayPctMap.get(symb);
        Decimal position = symbolPosMap.get(symb);
//        pr("Check Perc", symb, "3dp:", threeDayPerc, "1dp:", oneDayPerc, "pos:", position);

        if (oneDayPerc < 10 && checkDeltaImpact(symb, price)) {
            if (position.isZero()) {
                if (threeDayPerc < 40) {
                    outputToSymbol(symb, str("****FIRST****", t.format(f)));
                    outputToSymbol(symb, "****first buying", "3dp:",
                            threeDayPerc, "1dp:", oneDayPerc);
                    inventoryAdder(ct, price, t, getSizeFromPrice(price));
                }
            } else if (position.longValue() > 0 && costMap.containsKey(symb)) {
                if (priceDividedByCost(price, symb) < getRequiredRefillPoint(symb) && threeDayPerc < 40) {
                    outputToSymbol(symb, "****REFILL****", t.format(f));
                    outputToSymbol(symb, "buyMore:", "3dp:", threeDayPerc, "1dp:", oneDayPerc,
                            "costBasis:", costMap.getOrDefault(symb, 0.0),
                            "px/cost:", round5Digits(priceDividedByCost(price, symb)), "refill Price:"
                            , getRequiredRefillPoint(symb) * costMap.get(symb),
                            "avgRng:", averageDailyRange.getOrDefault(symb, 0.0));
                    inventoryAdder(ct, price, t, Decimal.get(5));
                }
            }
        } else if (oneDayPerc > 80 && threeDayPerc > 80 && position.longValue() > 0) {
            double priceOverCost = priceDividedByCost(price, symb);
            pr("priceOverCost", symb, priceDividedByCost(price, symb));
            if (priceOverCost > getRequiredProfitMargin(symb)) {
                outputToSymbol(symb, "****CUT****", t.format(f));
                outputToSymbol(symb, "Sell 1dP%:", oneDayPerc, "3dp:", threeDayPerc,
                        "priceOverCost:", round5Digits(priceOverCost),
                        "requiredMargin:", round5Digits(getRequiredProfitMargin(symb)), "avgRng:",
                        round5Digits(averageDailyRange.getOrDefault(symb, 0.0)));
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
                pr(t.format(simpleHrMinSec), "last px::", symb, price);
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);
                latestPriceTimeMap.put(symb, getESTLocalDateTimeNow());

                if (threeDayData.get(symb).containsKey(t.truncatedTo(MINUTES))) {
                    threeDayData.get(symb).get(t.truncatedTo(MINUTES)).add(price);
                } else {
                    threeDayData.get(symb).put(t.truncatedTo(MINUTES), new SimpleBar(price));
                }
                if (symbolPosMap.containsKey(symb)) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }
                tryToTrade(ct, price, t);
//                apiController.client().reqIds(-1);
//                apiController.
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

//        targetStockList.forEach(s -> {
//            double rng = ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30)).values().stream()
//                    .mapToDouble(SimpleBar::getHLRange).average().orElse(0.0);
////            pr("average range:", s, round5Digits(rng), "firstkey:",
////                    ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30)).firstKey(),
////                    "lastkey:", ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30))
////                            .lastKey(), "size:", ytdDayData.get(s).tailMap(LocalDate.now().minusDays(30)).size());
//            averageDailyRange.put(s, rng);
////            pr("refill point:", round5Digits(getRequiredRefillPoint(s)));
//        });

        targetStockList.forEach(symb -> {
            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {
                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME));

                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                if (symb.equalsIgnoreCase("WMT")) {
                    pr("compute:", symb, usTime(), "*3dP%:", threeDayPercentile,
                            "*1dP%:", oneDayPercentile, "last:",
                            latestPriceMap.getOrDefault(symb, 0.0), "*stats 1d:",
                            printStats(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
                    pr("stats 3d:", printStats(threeDayData.get(symb)));
                }
            }
        });

        aggregateDelta = targetStockList.stream().mapToDouble(s ->
                symbolPosMap.getOrDefault(s, Decimal.ZERO).
                        longValue() * latestPriceMap.getOrDefault(s, 0.0)).sum();

        targetStockList.forEach((s) ->
                symbolDeltaMap.put(s, (double) Math.round(symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue()
                        * latestPriceMap.getOrDefault(s, 0.0))));

        pr("aggregate Delta", r(aggregateDelta), symbolDeltaMap);

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

        outputToSymbol(symb, "3 Day Stats:", printStats(threeDayData.get(symb)),
                "1DStats:", printStats(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
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
                o.action(), "sell px:", offerPrice, "qty:", pos, "costBasis:", cost,
                orderSubmitted.get(symb).get(o.orderId()), "required Margin:", getRequiredProfitMargin(symb)
                , "targetPrice:", cost * getRequiredProfitMargin(symb),
                "askPrice", askMap.getOrDefault(symb, 0.0));

        outputToSymbol(symb, "3DStats:", printStats(threeDayData.get(symb)),
                "1DStats:", printStats(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
    }


    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        pr("openOrder call back");
        String symb = ibContractToSymbol(contract);
        outputToSymbol(symb, usTime(), "openOrder callback:", order, "orderState.status:", orderState.status());

        orderStatusMap.get(symb).put(order.orderId(), orderState.status());

        if (orderState.status() == Filled) {
            outputToFills(symb, usDateTime(), "openOrderCallback:filled", order);
            outputToSymbol(symb, usDateTime(), "openOrderCallback:filled", order);
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

        String symb = findSymbolByID(orderId);
        if (symb.equalsIgnoreCase("")) {
            outputToError("orderID not found:", orderId);
            return;
        }

        if (status == Filled) {
            outputToFills(symb, usDateTime(), "orderStatus Callback:filled", orderId);
            outputToSymbol(symb, usDateTime(), "orderStatus Callback:filled", orderId);
            if (openOrders.containsKey(symb) && openOrders.get(symb).containsKey(orderId)) {
                outputToFills(symb, usDateTime(), openOrders.get(symb).get(orderId));
            } else {
                outputToError(symb, usDateTime(), "order not in openOrderMap. ID:", orderId,
                        "status:", status, "filled:", filled, "remaining:", remaining,
                        "px:", avgFillPrice, "clientID:", clientId);
            }
        }

        orderStatusMap.forEach((k, v) -> {
            if (v.containsKey(orderId)) {
                orderStatusMap.get(k).put(orderId, status);
                outputToSymbol(k, usDateTime(), "OrderStatus callback::", "orderID:", orderId,
                        "status:", status, "filled:", filled, "remaining:", remaining,
                        "avgFillPrice:", avgFillPrice, "clientID:", clientId);
                outputToSymbol("Orderstatus::", status);
            } else {
                outputToError(usDateTime(),
                        "orderstatus Callback: orderID not in orderStatusMap:", orderId,
                        "status:", status, "filled:", filled, "remaining:", remaining,
                        "avgFillPrice:", avgFillPrice, "clientID:", clientId);
            }
        });

        outputToGeneral(usDateTime(), "openOrder orderStatus callback:", "orderId:", orderId,
                "status:", status, "filled:", filled, "remaining:", remaining,
                "fillPrice", avgFillPrice, "lastFillPrice:", lastFillPrice);

        if (status.isFinished()) {
            openOrders.forEach((k, v) -> {
                if (v.containsKey(orderId)) {
                    outputToSymbol(k, usDateTime(), "openOrder orderStatus:", status,
                            "Callback: deleting filled from open orders", openOrders);
                    outputToSymbol(k, usDateTime(), "status:", status,
                            "removing order from openOrders. OrderID:", orderId,
                            "order details:", v.get(orderId),
                            "remaining:", remaining);
                    v.remove(orderId);
                    outputToSymbol(k, "remaining open orders for ", k, v);
                    outputToSymbol(k, "remaining ALL open orders", openOrders);
                }
            });
        }
    }

    static String findSymbolByID(int id) {
        for (String k : orderStatusMap.keySet()) {
            if (orderStatusMap.get(k).containsKey(id)) {
                return k;
            }
        }
        return "";
    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToError("openOrder Error", usDateTime(), "orderId:",
                orderId, " errorCode:", errorCode, " msg:", errorMsg);

        if (errorCode == 2157) {
            pr("ignoring 2157", "orderID:", orderId, "msg:", errorMsg);
            return;
        }

//        outputToGeneral("openOrder ERROR:", usTime(), "orderId:",
//                orderId, " errorCode:", errorCode, " msg:", errorMsg);
    }

    //request realized pnl
    //Execution details *****************
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        String symb = ibContractToSymbol(contract);

        if (symb.startsWith("hk") || symb.startsWith("USD")) {
            return;
        }

        if (!tradeKeyExecutionMap.containsKey(tradeKey)) {
            tradeKeyExecutionMap.put(tradeKey, new LinkedList<>());
        }

        tradeKeyExecutionMap.get(tradeKey).add(new ExecutionAugmented(symb, execution));

        outputToSymbol(symb, usDateTime(), "tradeReport time:",
                executionToUSTime(execution.time()), execution.side(), "exec price:",
                execution.price(), "shares:", execution.shares(), "avgExecPrice:", execution.avgPrice());
    }

    @Override
    public void tradeReportEnd() {
        outputToGeneral(usDateTime(), "TradeReportEnd: all executions:");
        tradeKeyExecutionMap.values().stream().flatMap(Collection::stream)
                .collect(Collectors.groupingBy(ExecutionAugmented::getSymbol,
                        Collectors.mapping(ExecutionAugmented::getExec, Collectors.toList())))
                .forEach((key, value) -> outputToSymbol(key, "listOfExecutions",
                        value.stream().sorted(Comparator.comparingDouble(Execution::orderId)).toList()));
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


    //open orders end **********************
    public static void main(String[] args) {
        ProfitTargetTrader test1 = new ProfitTargetTrader();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCompute, 20L, 10L, TimeUnit.SECONDS);
        es.scheduleAtFixedRate(() -> {
            targetStockList.forEach(symb -> {
                outputToSymbol(symb,
                        latestPriceTimeMap.containsKey(symb) ? str(usTime(),
                                "last Live price feed time:",
                                latestPriceTimeMap.get(symb).format(simpleDayTime)
                                , "px:", latestPriceMap.getOrDefault(symb, 0.0)) : "no live feed");
                if (!orderStatusMap.get(symb).isEmpty()) {
                    outputToSymbol(symb, "periodic check:", usTime(),
                            "orderStatus", orderStatusMap.get(symb));
                }
                if (!openOrders.get(symb).isEmpty()) {
                    outputToSymbol(symb, "periodic check:", usTime(),
                            "openOrders", openOrders.get(symb));
                }
                outputToSymbol(symb, usDateTime(), "*3dP%:", threeDayPctMap.getOrDefault(symb, 0.0),
                        "*1dP%:", oneDayPctMap.getOrDefault(symb, 0.0));
                outputToSymbol(symb, "stats 3d:", printStats(threeDayData.get(symb)));
                outputToSymbol(symb, "*stats 1d:", printStats(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
            });
        }, 20L, 3600L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> outputToGeneral("*****Ending*****", usDateTime())));
    }
}
