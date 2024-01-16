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
import static java.lang.Double.MAX_VALUE;
import static java.lang.Math.round;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.stream.Collectors.*;
import static utility.Utility.*;

public class ProfitTargetTrader implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {
    private static ApiController api;
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();
    static final int MASTER_TRADE_ID = getSessionMasterTradeID();
    static volatile AtomicInteger tradeID = new AtomicInteger(MASTER_TRADE_ID + 1);

    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = GATEWAY_PORT;

    public static Map<String, Double> avgDailyRng = new HashMap<>();

    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");
    Contract ul = generateUSStockContract("UL");
    Contract mcd = generateUSStockContract("MCD");
    Contract spy = generateUSStockContract("SPY");
    Contract ko = generateUSStockContract("KO");
    Contract adbe = generateUSStockContract("ADBE");

    private ProfitTargetTrader() {
        outputToGeneral("*****START***** HK TIME:", hkTime(), "EST:", usDateTime(),
                "MASTER ID:", MASTER_TRADE_ID);
        pr("market start time today:", TODAY930);
        pr("until market start time:", Duration.between(TODAY930,
                getESTLocalDateTimeNow()).toMinutes(), "minutes");
        registerContractAll(wmt, pg, ul, mcd, spy, ko, adbe);
    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new DefaultLogger(), new DefaultLogger());
        api = ap;
        CountDownLatch l = new CountDownLatch(1);

        try {
            ap.connect("127.0.0.1", PORT_TO_USE, 5, "");
            l.countDown();
            pr(" Latch counted down", PORT_TO_USE, getESTLocalDateTimeNow().format(MdHmm));
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
                if (!twoDData.containsKey(symb)) {
                    twoDData.put(symb, new ConcurrentSkipListMap<>());
                }
                pr("requesting hist day data", symb);
                CompletableFuture.runAsync(() -> reqHistDayData(api, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::todaySoFar, () ->
                                pr(symb, "2DStats:" + genStatsString(twoDData.get(symb)),
                                        "1DStats:" + genStatsString(twoDData.get(symb).tailMap(TODAY230))),
                        2, Types.BarSize._1_min));
                CompletableFuture.runAsync(() -> reqHistDayData(api, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::ytdOpen, () -> computeHistoricalData(symb)
                        , Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day));
            });
            api.reqPositions(this);
            api.reqLiveOrders(this);

            pr("req Executions");
            api.reqExecutions(new ExecutionFilter(), this);
            outputToGeneral(usDateTime(), "cancelling all orders on start up");
            api.cancelAllOrders();
        }, 2, TimeUnit.SECONDS);
    }

    static void computeHistoricalData(String s) {
        if (ytdDayData.containsKey(s) && !ytdDayData.get(s).isEmpty()) {
            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
                    .average().orElse(0.0);
            pr("average range:", s, round4(rng));
            avgDailyRng.put(s, rng);
            outputToSymbol(s, usDateTime(), "avgRange:" + round4(rng), "refillP%:" + getRefillPercent(s));
            outputToSymbol(s, usDateTime(), "reqMargin:" + round4(getReqMargin(s)));


            if (ytdDayData.get(s).firstKey().isBefore(getYearBeginMinus1Day())) {
                double lastYearClose = ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                lastYearCloseMap.put(s, lastYearClose);
                pr("last year close for", s, ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getKey(),
                        ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose(),
                        "YTD:", round2(ytdDayData.get(s).lastEntry().getValue().getClose() / lastYearClose - 1));
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
        outputToSymbol(symb, "*STARTS*", usDateTime());
        symbolContractMap.put(symb, ct);
        targetStockList.add(symb);
        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
        orderStatus.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
        if (!ytdDayData.containsKey(symb)) {
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    static boolean noBlockingOrders(String s) {
        if (!orderStatus.get(s).isEmpty()) {
            pr(s, "no blocking orders check:", orderStatus.get(s));
        }
        return orderStatus.get(s).isEmpty() ||
                orderStatus.get(s).values().stream().allMatch(OrderStatus::isFinished);
    }

    private static double pxOverCost(double price, String symb) {
        if (avgCost.containsKey(symb) && avgCost.get(symb) != 0.0) {
            return price / avgCost.get(symb);
        }
        return 1;
    }

    private static boolean checkDeltaImpact(String symb, double price) {
        double addition = getAddSize(symb, price).longValue() * price;

//        pr(symb, "check delta impact", "nowDelta+addition<TotalLimit:",
//                aggregateDelta + addition < DELTA_TOTAL_LIMIT,
//                "deltaStock+Inc<Limit:", symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
//                        getSizeFromPrice(price).longValue() * price < DELTA_EACH_LIMIT);

        return aggregateDelta + addition < DELTA_TOTAL_LIMIT &&
                (symbDelta.getOrDefault(symb, MAX_VALUE) +
                        addition < DELTA_EACH_LIMIT);
    }

    static double getRefillPx(String symb, double price, long position, double costBasis) {
        if (price == 0.0 || position == 0.0 || costBasis == 0.0) {
            return 0.0;
        }
        double currentDelta = price * position;
        double refillPercent = getRefillPercent(symb);
        double addSize = getAddSize(symb, price).longValue();

        return Math.min(price,
                (costBasis * refillPercent * (position + addSize) - currentDelta) / addSize);
    }

    static void tryToTrade(Contract ct, double px, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            return;
        }

        String symb = ibContractToSymbol(ct);
        if (!noBlockingOrders(symb)) {
            outputToSymbol(symb, t.format(Hmmss), "order blocked by:" +
                    openOrders.get(symb).values(), "orderStatus:" + orderStatus.get(symb));
            return;
        }

        if (!ct.currency().equalsIgnoreCase("USD")) {
            outputToGeneral(usDateTime(), "only USD stock allowed, symb:", ct.symbol());
            return;
        }

        if (!twoDayPctMap.containsKey(symb) || !oneDayPctMap.containsKey(symb)) {
            pr(symb, "no percentile info:", !twoDayPctMap.containsKey(symb) ? "2day" : "",
                    !oneDayPctMap.containsKey(symb) ? "1day" : "");
            return;
        }

        double twoDayP = twoDayPctMap.get(symb);
        double oneDayP = oneDayPctMap.get(symb);
        Decimal pos = symbolPos.get(symb);

        if (oneDayP < 10 && twoDayP < 20 && checkDeltaImpact(symb, px)) {
            if (pos.isZero()) {
                outputToSymbol(symb, "*1st Buy*", t.format(MdHmmss), "1dp:" + oneDayP, "2dp:" + twoDayP);
                inventoryAdder(ct, px, t, getAddSize(symb, px));
            } else if (pos.longValue() > 0 && avgCost.getOrDefault(symb, 0.0) != 0.0) {
                if (px < getRefillPx(symb, px, pos.longValue(), avgCost.get(symb))) {
                    outputToSymbol(symb, "*REFILL*", t.format(MdHmmss),
                            "deltaNow:" + symbDelta.getOrDefault(symb, 0.0),
                            "1dp:" + oneDayP, "2dp:" + twoDayP,
                            "avgCost:" + avgCost.get(symb),
                            "px/cost:" + round4(pxOverCost(px, symb)),
                            "refillPx:" + (round2(getRefillPx(symb, px, pos.longValue(), avgCost.get(symb)))),
                            "avgRng:" + avgDailyRng.getOrDefault(symb, 0.0));
                    inventoryAdder(ct, px, t, getAddSize(symb, px));
                }
            }
        } else if (oneDayP > 80 && pos.longValue() > 0) {
            double pOverCost = pxOverCost(px, symb);
            pr("px/Cost", symb, pxOverCost(px, symb));
            if (pOverCost > getReqMargin(symb)) {
                outputToSymbol(symb, "****CUT****", t.format(MdHmmss),
                        "1dP:" + oneDayP, "2dp:" + twoDayP,
                        "px/Cost:" + round4(pOverCost),
                        "reqMargin:" + round4(getReqMargin(symb)),
                        "avgRng:" + round4(avgDailyRng.getOrDefault(symb, 0.0)));
                inventoryCutter(ct, px, t);
            }
        }
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr(t.format(Hmmss), "last px::", symb, price);
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);
                latestPriceTimeMap.put(symb, getESTLocalDateTimeNow());

                if (twoDData.get(symb).containsKey(t.truncatedTo(MINUTES))) {
                    twoDData.get(symb).get(t.truncatedTo(MINUTES)).add(price);
                } else {
                    twoDData.get(symb).put(t.truncatedTo(MINUTES), new SimpleBar(price));
                }
                if (symbolPos.containsKey(symb)) {
                    symbDelta.put(symb, price * symbolPos.get(symb).longValue());
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
            symbolPos.put(symb, position);
            Allstatic.avgCost.put(symb, avgCost);
            outputToSymbol(symb, "updating position:", usDateTime(),
                    "position:" + position, "cost:" + avgCost);
        }
    }

    @Override
    public void positionEnd() {
        pr(usDateTime(), "position end");
        targetStockList.forEach(symb -> {
            if (!symbolPos.containsKey(symb)) {
                symbolPos.put(symb, Decimal.ZERO);
            }

            outputToSymbol(symb, "POS COST", symbolPos.get(symb).longValue(),
                    avgCost.getOrDefault(symb, 0.0),
                    "DELTA:" + symbolPos.get(symb).longValue() * avgCost.getOrDefault(symb, 0.0));


            api.reqContractDetails(symbolContractMap.get(symb), list -> list.forEach(a ->
                    symbolContractIDMap.put(symb, a.contract().conid())));

            es.schedule(() -> {
                pr("Position end: requesting live:", symb);
                req1ContractLive(api, symbolContractMap.get(symb), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    private static void periodicCompute() {
        targetStockList.forEach(symb -> {
            if (symbolPos.containsKey(symb)) {
                if (latestPriceMap.getOrDefault(symb, 0.0) != 0.0 && avgCost.getOrDefault(symb, 0.0) != 0.0) {
                    pr(symb, usTime(), "position:" + symbolPos.get(symb),
                            "px:" + latestPriceMap.get(symb),
                            "cost:" + r(avgCost.get(symb)),
                            "rtn:" + round(1000.0 * (latestPriceMap.get(symb) / avgCost.get(symb) - 1)) / 10.0 + "%",
                            "refillPx:" + getRefillPx(symb, latestPriceMap.get(symb)
                                    , symbolPos.get(symb).longValue(), avgCost.get(symb)),
                            "refillPx/Cost:" + round4(getRefillPx(symb, latestPriceMap.get(symb)
                                    , symbolPos.get(symb).longValue(), avgCost.get(symb)) / avgCost.get(symb)),
                            "refillPx/Px:" + round4(getRefillPx(symb, latestPriceMap.get(symb)
                                    , symbolPos.get(symb).longValue(), avgCost.get(symb))
                                    / latestPriceMap.get(symb)),
                            "1dp:" + oneDayPctMap.getOrDefault(symb, 0.0),
                            "2dp:" + twoDayPctMap.getOrDefault(symb, 0.0));
                }
            }
        });

        targetStockList.forEach(symb -> {
            if (twoDData.containsKey(symb) && !twoDData.get(symb).isEmpty()) {
                double twoDayPercentile = calculatePercentileFromMap(twoDData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(twoDData.get(symb)
                        .tailMap(TODAY230));

                twoDayPctMap.put(symb, twoDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
            }
        });

        aggregateDelta = targetStockList.stream().mapToDouble(s ->
                symbolPos.getOrDefault(s, Decimal.ZERO).
                        longValue() * latestPriceMap.getOrDefault(s, 0.0)).sum();

        targetStockList.forEach((s) ->
                symbDelta.put(s, (double) round(symbolPos.getOrDefault(s, Decimal.ZERO)
                        .longValue() * latestPriceMap.getOrDefault(s, 0.0))));

        pr("aggregate Delta", r(aggregateDelta), symbDelta);

        openOrders.forEach((k, v) -> v.forEach((k1, v1) -> {
            if (orderStatus.get(k).get(k1).isFinished()) {
                outputToSymbol(k, "in compute: removing finished orders", "ordID:" +
                        k1, "order:" + v1);
                v.remove(k1);
            }
        }));
    }

    private static void inventoryAdder(Contract ct, double price, LocalDateTime t, Decimal buySize) {
        String s = ibContractToSymbol(ct);

        if (symbDelta.getOrDefault(s, MAX_VALUE) + buySize.longValue() * price > deltaLimitEach(s)) {
            outputToSymbol(s, usDateTime(), "buy exceeds lmt. deltaNow:" + symbDelta.getOrDefault(s, MAX_VALUE),
                    "addDelta:" + buySize.longValue() * price);
            return;
        }
        int id = tradeID.incrementAndGet();
        double bidPrice = r(Math.min(price, bidMap.getOrDefault(s, price)));
        Order o = placeBidLimitTIF(id, bidPrice, buySize, DAY);
        orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_ADDER));
        orderStatus.get(s).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
        outputToSymbol(s, "ordID:" + o.orderId(), "tradID:" + id, o.action(),
                "px:" + bidPrice, "size:" + buySize, orderSubmitted.get(s).get(o.orderId()));
        outputToSymbol(s, "2DStats:" + genStatsString(twoDData.get(s)),
                "1DStats:" + genStatsString(twoDData.get(s).tailMap(TODAY230)));
    }

    private static void inventoryCutter(Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPos.get(symb);

        int id = tradeID.incrementAndGet();
        double cost = avgCost.getOrDefault(symb, MAX_VALUE);
        double offerPrice = r(Math.max(askMap.getOrDefault(symb, price),
                avgCost.getOrDefault(symb, MAX_VALUE) * getReqMargin(symb)));

        Order o = placeOfferLimitTIF(id, offerPrice, pos, DAY);
        orderSubmitted.get(symb).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
        orderStatus.get(symb).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(symb, o.orderId()));
        outputToSymbol(symb, "ordID:" + o.orderId(), "tradID:" + id, o.action(), "Px:" + offerPrice,
                "qty:" + o.totalQuantity().longValue(), "cost:" + round2(cost),
                orderSubmitted.get(symb).get(o.orderId()),
                "reqMargin:" + getReqMargin(symb),
                "targetPx:" + round2(cost * getReqMargin(symb)),
                "askPx:" + askMap.getOrDefault(symb, 0.0));
        outputToSymbol(symb, "2DStats:" + genStatsString(twoDData.get(symb)));
        outputToSymbol(symb, "1DStats:" + genStatsString(twoDData.get(symb).tailMap(TODAY230)));
    }

    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String s = ibContractToSymbol(contract);
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
            outputToSymbol(s, usDateTime(), "*openOrder*:after removal." +
                    "open orders:", s, openOrders.get(s));
        } else { //order is not finished
            openOrders.get(s).put(order.orderId(), order);
        }
        if (!openOrders.get(s).isEmpty()) {
            outputToSymbol(s, usDateTime(), "*openOrder* all live orders", openOrders.get(s));
        }
    }

    @Override
    public void openOrderEnd() {
        outputToGeneral(usDateTime(), "*openOrderEnd*: print all openOrders", openOrders,
                "orderStatusMap:", orderStatus);
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {

        outputToGeneral(usDateTime(), "*OrderStatus*:" + status, "orderId:" + orderId,
                "filled:" + filled.longValue(), "remaining:" + remaining,
                "fillPx" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        String symb = findSymbolByID(orderId);
        if (symb.equalsIgnoreCase("")) {
            outputToError("*orderStatus* orderID not found:", orderId);
            return;
        }

        outputToSymbol(symb, usDateTime(), "*OrderStatus*:" + status,
                "orderId:" + orderId, "filled:" + filled, "remaining:" + remaining,
                "fillPx" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        if (status == Filled) {
            outputToFills(symb, usDateTime(), "*OrderStatus*: filled. ordID:" + orderId);
        }

        //put status in orderstatusmap
        orderStatus.get(symb).put(orderId, status);

        //removing finished orders
        if (status.isFinished()) {
            if (openOrders.get(symb).containsKey(orderId)) {
                outputToSymbol(symb, usDateTime(), "*OrderStatus*:" + status,
                        "deleting finished orders from openOrderMap", openOrders.get(symb));
                openOrders.get(symb).remove(orderId);
                outputToSymbol(symb, "*OrderStatus* remaining openOrders", status, openOrders.get(symb));
                outputToSymbol(symb, "*OrderStatus* print ALL openOrders:", openOrders);
            }
        }
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
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToError("openOrder Error", usDateTime(), "orderId:" +
                orderId, " errorCode:" + errorCode, " msg:" + errorMsg);
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
                executionToUSTime(execution.time()), execution.side(),
                "execPx:" + execution.price(), "shares:" + execution.shares(),
                "avgPx:" + execution.avgPrice());

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
                .forEach((key, value) -> outputToSymbol(key, "listOfExecs",
                        value.stream().sorted(Comparator.comparingDouble(Execution::orderId))
                                .collect(Collectors.toList())));
    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {
        if (!tradeKeyExecutionMap.containsKey(tradeKey)
                || tradeKeyExecutionMap.get(tradeKey).isEmpty()) {
            return;
        }

        String symb = tradeKeyExecutionMap.get(tradeKey).get(0).getSymbol();

        if (orderSubmitted.containsKey(symb) && !orderSubmitted.get(symb).isEmpty()) {
            orderSubmitted.get(symb).entrySet().stream().filter(e1 -> e1.getValue().getOrder().orderId()
                            == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId())
                    .forEach(e2 -> {
                        String outp = str("1.*commission report* orderID:" + e2.getKey(),
                                "commission:" + commissionReport.commission(),
                                e2.getValue().getOrder().action() == SELL ?
                                        str("orderID:", e2.getKey(), "realized pnl:",
                                                commissionReport.realizedPNL()) : "");
                        outputToSymbol(symb, outp);
                        outputToFills(symb, outp);
                    });

            orderSubmitted.get(symb).forEach((key1, value1) -> {
                if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId()) {
                    outputToSymbol(symb, "2.*commission report* orderID:" + value1.getOrder().orderId(),
                            "commission:", commissionReport.commission(),
                            value1.getOrder().action() == SELL ?
                                    str("realized pnl:", commissionReport.realizedPNL()) : "");
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
                outputToSymbol(symb, "*Periodic Run*", usDateTime());
                outputToSymbol(symb,
                        latestPriceTimeMap.containsKey(symb) ?
                                str("last Live feed time:", latestPriceTimeMap.get(symb).format(MdHmm)
                                        , "px:" + latestPriceMap.getOrDefault(symb, 0.0),
                                        avgCost.getOrDefault(symb, 0.0) == 0.0 ? "" : str("px/cost:" +
                                                round4(latestPriceMap.getOrDefault(symb, 0.0)
                                                        / avgCost.getOrDefault(symb, 0.0)))) :
                                str("no live feed"));
                outputToSymbol(symb, "delta:" + symbDelta.getOrDefault(symb, 0.0));
                if (symbDelta.getOrDefault(symb, 0.0) > 0.0) {
                    outputToSymbol(symb, "refillPx:" +
                                    getRefillPx(symb, latestPriceMap.get(symb),
                                            symbolPos.get(symb).longValue()
                                            , avgCost.get(symb)),
                            "refillP%:" + getRefillPercent(symb),
                            "refillPx/cost:" +
                                    round3(getRefillPx(symb, latestPriceMap.get(symb),
                                            symbolPos.get(symb).longValue()
                                            , avgCost.get(symb)) / avgCost.get(symb)));
                }
                if (!orderStatus.get(symb).isEmpty()) {
                    outputToSymbol(symb, usDateTime(), "*chek orderStatus", orderStatus.get(symb));
                }
                if (!openOrders.get(symb).isEmpty()) {
                    outputToSymbol(symb, usDateTime(), "*chek openOrders*:", openOrders.get(symb));
                }
                outputToSymbol(symb, usDateTime(), "2dP:" + twoDayPctMap.getOrDefault(symb, 0.0),
                        "1dP:" + oneDayPctMap.getOrDefault(symb, 0.0));
                outputToSymbol(symb, "*2dStats:" + genStatsString(twoDData.get(symb)));
                outputToSymbol(symb, "*1dStats:" + genStatsString(twoDData.get(symb).tailMap(TODAY230)));
            });
        }, 20L, 3600L, TimeUnit.SECONDS);
        Runtime.getRuntime().

                addShutdownHook(new Thread(() ->

                        outputToGeneral("*Ending*", usDateTime())));
    }
}
