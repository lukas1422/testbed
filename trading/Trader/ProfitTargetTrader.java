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
import static client.Types.Action.SELL;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static Trader.TradingUtility.*;
import static java.lang.Math.round;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.stream.Collectors.*;
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
        outputToGeneral("*****START***** HK TIME:", hkTime(), "EST:", usTime(),
                "MASTER ID:", MASTER_TRADE_ID);
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
                if (!twoDayData.containsKey(symb)) {
                    twoDayData.put(symb, new ConcurrentSkipListMap<>());
                }
                pr("requesting hist day data", symb);
                CompletableFuture.runAsync(() -> reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::todaySoFar, () ->
                                pr(symb, "3 Day Stats:", genStatsString(twoDayData.get(symb)),
                                        "1DStats:", genStatsString(twoDayData.get(symb).tailMap(PERCENTILE_START_TIME))), 2, Types.BarSize._1_min));
                CompletableFuture.runAsync(() -> reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::ytdOpen, () -> computeHistoricalData(symb)
                        , Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day));
            });
            apiController.reqPositions(this);
            apiController.reqLiveOrders(this);

            pr("req Executions");
            apiController.reqExecutions(new ExecutionFilter(), this);
            outputToGeneral(usDateTime(), "cancelling all orders on start up");
            apiController.cancelAllOrders();

        }, 2, TimeUnit.SECONDS);
    }

    static void computeHistoricalData(String s) {
        if (ytdDayData.containsKey(s) && !ytdDayData.get(s).isEmpty()) {
            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
                    .average().orElse(0.0);
            pr("average range:", s, round5Digits(rng));
            averageDailyRange.put(s, rng);
            outputToSymbol(s, usDateTime(),
                    "profit margin required:", round5Digits(getRequiredProfitMargin(s)));

            if (ytdDayData.get(s).firstKey().isBefore(getYearBeginMinus1Day())) {
                double lastYearClose = ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                lastYearCloseMap.put(s, lastYearClose);
                pr("last year close for", s, ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getKey(),
                        ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose(),
                        "YTD:", round2Digits(ytdDayData.get(s).lastEntry().getValue().getClose() / lastYearClose - 1));
            }
        } else {
            pr("no historical data to compute ", s);
        }
    }

    private static void registerContractAll(Contract... cts) {
        Arrays.stream(cts).forEach(ProfitTargetTrader::registerContract);
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        outputToSymbol(symb, "*******************************************");
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
        double addition = getSizeFromPrice(price).longValue() * price;

        pr(symb, "check delta impact", "aggDelta+addition<Delta Limit:",
                aggregateDelta + addition < DELTA_LIMIT,
                "Current+Inc<Stock Limit:", symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
                        getSizeFromPrice(price).longValue() * price < DELTA_LIMIT_EACH_STOCK);
        return aggregateDelta + addition < DELTA_LIMIT &&
                (symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
                        addition < DELTA_LIMIT_EACH_STOCK);
    }

    static void tryToTrade(Contract ct, double price, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            return;
        }

        String symb = ibContractToSymbol(ct);
        if (!noBlockingOrders(symb)) {
            outputToSymbol(symb, t.format(simpleHrMinSec), "order blocked:", symb,
                    openOrders.get(symb).values(), "orderStatus:", orderStatusMap.get(symb));
            return;
        }

        if (!ct.currency().equalsIgnoreCase("USD")) {
            pr("only USD stock allowed, symb:", ct.symbol());
            return;
        }

        if (!twoDayPctMap.containsKey(symb) || !oneDayPctMap.containsKey(symb)) {
            pr(symb, "no percentile info:", !twoDayPctMap.containsKey(symb) ? "2day" : "",
                    !oneDayPctMap.containsKey(symb) ? "1day" : "");
            return;
        }

        double twoDayPerc = twoDayPctMap.get(symb);
        double oneDayPerc = oneDayPctMap.get(symb);
        Decimal position = symbolPosMap.get(symb);

        if (oneDayPerc < 10 && checkDeltaImpact(symb, price) && twoDayPerc < 40) {
            if (position.isZero()) {
                outputToSymbol(symb, str("****FIRST****", t.format(f)));
                outputToSymbol(symb, "****first buying", "2dp:",
                        twoDayPerc, "1dp:", oneDayPerc);
                inventoryAdder(ct, price, t, getSizeFromPrice(price));
            } else if (position.longValue() > 0 && costMap.containsKey(symb)) {
                if (priceDividedByCost(price, symb) < getRequiredRefillPoint(symb)) {
                    outputToSymbol(symb, "****REFILL****", t.format(f));
                    outputToSymbol(symb, "buyMore: 2dp:", twoDayPerc, "1dp:", oneDayPerc,
                            "costBasis:", costMap.getOrDefault(symb, 0.0),
                            "px/cost:", round5Digits(priceDividedByCost(price, symb)),
                            "refill Price:"
                            , round2Digits(getRequiredRefillPoint(symb) * costMap.get(symb)),
                            "avgRng:", averageDailyRange.getOrDefault(symb, 0.0));
                    inventoryAdder(ct, price, t, getSizeFromPrice(price));
                }
            }
        } else if (oneDayPerc > 80 && position.longValue() > 0) {
            double priceOverCost = priceDividedByCost(price, symb);
            pr("priceOverCost", symb, priceDividedByCost(price, symb));
            if (priceOverCost > getRequiredProfitMargin(symb)) {
                outputToSymbol(symb, "****CUT****", t.format(f));
                outputToSymbol(symb, "Sell 1dP:", oneDayPerc, "2dp:", twoDayPerc,
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

                if (twoDayData.get(symb).containsKey(t.truncatedTo(MINUTES))) {
                    twoDayData.get(symb).get(t.truncatedTo(MINUTES)).add(price);
                } else {
                    twoDayData.get(symb).put(t.truncatedTo(MINUTES), new SimpleBar(price));
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
            outputToSymbol(symb, "updating position:", usDateTime(),
                    "position:", position, "cost:", avgCost);
        }
    }

    @Override
    public void positionEnd() {
        pr(usTime(), "position end");
        targetStockList.forEach(symb -> {
            if (!symbolPosMap.containsKey(symb)) {
                symbolPosMap.put(symb, Decimal.ZERO);
            }

            outputToSymbol(symb, "POS COST", symbolPosMap.get(symb).longValue(), costMap.getOrDefault(symb, 0.0));

            apiController.reqContractDetails(symbolContractMap.get(symb), list -> list.forEach(a ->
                    symbolContractIDMap.put(symb, a.contract().conid())));

            es.schedule(() -> {
                pr("Position end: requesting live:", symb);
                req1ContractLive(apiController, symbolContractMap.get(symb), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    private static void periodicCompute() {
        targetStockList.forEach(symb -> {
            if (symbolPosMap.containsKey(symb)) {
                if (latestPriceMap.containsKey(symb) && costMap.getOrDefault(symb, 0.0) != 0.0) {
                    pr(symb, usTime(), "position:", symbolPosMap.get(symb),
                            "price", latestPriceMap.get(symb),
                            "cost:", r(costMap.get(symb)), "rtn:",
                            round(1000.0 * (latestPriceMap.get(symb) / costMap.get(symb) - 1)) / 10.0, "%",
                            "1dp:", oneDayPctMap.getOrDefault(symb, 0.0),
                            "2dp:", twoDayPctMap.getOrDefault(symb, 0.0));
                }
            }
        });

        targetStockList.forEach(symb -> {
            if (twoDayData.containsKey(symb) && !twoDayData.get(symb).isEmpty()) {
                double twoDayPercentile = calculatePercentileFromMap(twoDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(twoDayData.get(symb).tailMap(PERCENTILE_START_TIME));

                twoDayPctMap.put(symb, twoDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
//                pr("compute:", symb, usTime(), "*2dP%:", round(twoDayPercentile),
//                        "*1dP%:", round(oneDayPercentile), "last:",
//                        latestPriceMap.getOrDefault(symb, 0.0));
//                pr(symb, "*stats 1d:",
//                        genStatsString(threeDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
//                pr(symb, "stats 2d:", genStatsString(threeDayData.get(symb)));
            }
        });

        aggregateDelta = targetStockList.stream().mapToDouble(s ->
                symbolPosMap.getOrDefault(s, Decimal.ZERO).
                        longValue() * latestPriceMap.getOrDefault(s, 0.0)).sum();

        targetStockList.forEach((s) ->
                symbolDeltaMap.put(s, (double) round(symbolPosMap.getOrDefault(s, Decimal.ZERO)
                        .longValue() * latestPriceMap.getOrDefault(s, 0.0))));

        pr("aggregate Delta", r(aggregateDelta), symbolDeltaMap);

        openOrders.forEach((k, v) -> v.forEach((k1, v1) -> {
            if (orderStatusMap.get(k).get(k1).isFinished()) {
                outputToSymbol(k, "in compute: removing finished orders", "orderID:",
                        k1, "order:", v1);
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
        outputToSymbol(symb, "2DStats:", genStatsString(twoDayData.get(symb)),
                "1DStats:", genStatsString(twoDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
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
                o.action(), "sell px:", offerPrice, "qty:", o.totalQuantity().longValue(), "costBasis:", cost,
                orderSubmitted.get(symb).get(o.orderId()),
                "required Margin:", getRequiredProfitMargin(symb)
                , "targetPrice:", cost * getRequiredProfitMargin(symb),
                "askPrice", askMap.getOrDefault(symb, 0.0));

        outputToSymbol(symb, "2DStats:", genStatsString(twoDayData.get(symb)),
                "1DStats:", genStatsString(twoDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
    }

    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String symb = ibContractToSymbol(contract);
        outputToSymbol(symb, usDateTime(), "*openOrder*", "status:", orderState.status(), order);
        orderStatusMap.get(symb).put(order.orderId(), orderState.status());

        if (orderState.status() == Filled) {
            outputToFills(symb, usDateTime(), "*openOrder* filled", order);
        }

        if (orderState.status().isFinished()) {
            outputToSymbol(symb, usDateTime(), "*openOrder*:removing order. Status:",
                    orderState.status(), order);
            if (openOrders.get(symb).containsKey(order.orderId())) {
                openOrders.get(symb).remove(order.orderId());
            }
            outputToSymbol(symb, usDateTime(), "*openOrder*:after removal." +
                    "open orders:", symb, openOrders.get(symb));
        } else { //order is not finished
            openOrders.get(symb).put(order.orderId(), order);
        }
        if (!openOrders.get(symb).isEmpty()) {
            outputToSymbol(symb, usDateTime(), "*openOrder* all live orders", openOrders.get(symb));
        }
    }

    @Override
    public void openOrderEnd() {
        outputToGeneral(usDateTime(), "*openOrderEnd*: print all openOrders", openOrders,
                "orderStatusMap:", orderStatusMap);
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {

        outputToGeneral(usDateTime(), "*OrderStatus*:", status, "orderId:", orderId,
                "filled:", filled, "remaining:", remaining,
                "fillPx", avgFillPrice, "lastFillPx:", lastFillPrice);

        String symb = findSymbolByID(orderId);
        if (symb.equalsIgnoreCase("")) {
            outputToError("*orderStatus* orderID not found:", orderId);
            return;
        }

        outputToSymbol(symb, usDateTime(), "*OrderStatus*:", status,
                "orderId:", orderId, "filled:", filled, "remaining:", remaining,
                "fillPrice", avgFillPrice, "lastFillPrice:", lastFillPrice);

        if (status == Filled) {
            outputToFills(symb, usDateTime(), "*OrderStatus*: filled. orderID:", orderId);
        }

        //put status in orderstatusmap
        orderStatusMap.get(symb).put(orderId, status);

        //removing finished orders
        if (status.isFinished()) {
            if (openOrders.get(symb).containsKey(orderId)) {
                outputToSymbol(symb, usDateTime(), "*OrderStatus*", status,
                        "deleting finished orders from openOrderMap", openOrders.get(symb));
                openOrders.get(symb).remove(orderId);
                outputToSymbol(symb, "*OrderStatus* remaining openOrders for name",
                        status, openOrders.get(symb));
                outputToSymbol(symb, "*OrderStatus* print aLL open orders:", openOrders);
            }
        }
    }

    private static String findSymbolByID(int id) {
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
    }

    //request realized pnl
    //Execution details *****************
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        String symb = ibContractToSymbol(contract);

        if (symb.equalsIgnoreCase("USD")) {
            return;
        }

        outputToSymbol(symb, usDateTime(), "*tradeReport* time:",
                executionToUSTime(execution.time()), execution.side(), "exec price:",
                execution.price(), "shares:", execution.shares(),
                "avgExecPrice:", execution.avgPrice());

        if (!tradeKeyExecutionMap.containsKey(tradeKey)) {
            tradeKeyExecutionMap.put(tradeKey, new LinkedList<>());
        }
        tradeKeyExecutionMap.get(tradeKey).add(new ExecutionAugmented(symb, execution));
    }

    @Override
    public void tradeReportEnd() {
        outputToGeneral(usDateTime(), "*TradeReportEnd*: all executions:", tradeKeyExecutionMap);
        tradeKeyExecutionMap.values().stream().flatMap(Collection::stream)
                .collect(groupingBy(ExecutionAugmented::getSymbol,
                        mapping(ExecutionAugmented::getExec, toList())))
                .forEach((key, value) -> outputToSymbol(key, "listOfExecutions",
                        value.stream().sorted(Comparator.comparingDouble(Execution::orderId))
                                .collect(Collectors.toList())));
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
                    .forEach(e2 -> {
                        String outp = str("1.*commission report*",
                                "orderID:", e2.getKey(), "commission:", commissionReport.commission(),
                                e2.getValue().getOrder().action() == SELL ?
                                        str("orderID:", e2.getKey(), "realized pnl:",
                                                commissionReport.realizedPNL()) : "NO PNL");
                        outputToSymbol(symb, outp);
                        outputToFills(symb, outp);
                    });

            orderSubmitted.get(symb).forEach((key1, value1) -> {
                if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId()) {
                    outputToSymbol(symb, "2.*commission report*", "orderID:", value1.getOrder().orderId(), "commission:",
                            commissionReport.commission(), value1.getOrder().action() == SELL ?
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
                outputToSymbol(symb, "****Periodic Run****", usDateTime());
                outputToSymbol(symb,
                        latestPriceTimeMap.containsKey(symb) ? str(usDateTime(),
                                "last Live feed time:",
                                latestPriceTimeMap.get(symb).format(simpleDayTime)
                                , "px:", latestPriceMap.getOrDefault(symb, 0.0),
                                costMap.getOrDefault(symb, 0.0) == 0.0 ? "" : str("px/cost:",
                                        round5Digits(latestPriceMap.getOrDefault(symb, 0.0)
                                                / costMap.getOrDefault(symb, 0.0)))) :
                                str(usDateTime(), "no live feed"));
                if (!orderStatusMap.get(symb).isEmpty()) {
                    outputToSymbol(symb, "*check orderStatus*:", usDateTime(),
                            "orderStatus", orderStatusMap.get(symb));
                }
                if (!openOrders.get(symb).isEmpty()) {
                    outputToSymbol(symb, "*check openOrders*:", usDateTime(),
                            "openOrders", openOrders.get(symb));
                }
                outputToSymbol(symb, usDateTime(), "*2dP%:", twoDayPctMap.getOrDefault(symb, 0.0),
                        "*1dP%:", oneDayPctMap.getOrDefault(symb, 0.0));
                outputToSymbol(symb, "*2d*:", genStatsString(twoDayData.get(symb)));
                outputToSymbol(symb, "*1d*:", genStatsString(twoDayData.get(symb).tailMap(PERCENTILE_START_TIME)));
            });
        }, 20L, 3600L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> outputToGeneral("****Ending****", usDateTime())));
    }
}
