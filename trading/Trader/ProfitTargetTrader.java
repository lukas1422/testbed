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
    private static volatile TreeSet<String> targetList = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();
    static final int MASTERID = getSessionMasterTradeID();
    static volatile AtomicInteger tradID = new AtomicInteger(MASTERID + 1);

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

    private ProfitTargetTrader() {
        outputToGeneral("*****START***** HKT:", hkTime(), "EST:", usDateTime(), "MASTERID:", MASTERID);
        pr("mkt start time today:", TODAY930);
        pr("until mkt start time:", Duration.between(TODAY930, getESTDateTimeNow()).toMinutes(), "mins");
        registerContractAll(wmt, pg, ul, mcd, spy, ko);
    }

    private void connectAndReqPos() {
        api = new ApiController(new DefaultConnectionHandler(), new DefaultLogger(), new DefaultLogger());
        //api = ap;
        CountDownLatch l = new CountDownLatch(1);

        try {
            api.connect("127.0.0.1", PORT_TO_USE, 5, "");
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

        Executors.newScheduledThreadPool(10).schedule(() -> {
            targetList.forEach(symb -> {
                Contract c = symbolContractMap.get(symb);
                if (!twoDayData.containsKey(symb)) {
                    twoDayData.put(symb, new ConcurrentSkipListMap<>());
                }
                pr("requesting hist day data", symb);
                CompletableFuture.runAsync(() -> reqHistDayData(api, Allstatic.ibStockReqId.addAndGet(5),
                        c, Allstatic::todaySoFar, () ->
                                pr(symb, "2DStats:" + genStatsString(twoDayData.get(symb)),
                                        "1DStats:" + genStatsString(twoDayData.get(symb).tailMap(TODAY230))),
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
//            pr("average range:", s, round4(rng));
            avgDailyRng.put(s, rng);
            outputToSymbol(s, usDateTime(), "avgRange:" + round4(rng),
                    "reduceCostTarget:" + round5(reduceCostTgt(s)));
            outputToSymbol(s, usDateTime(), "tgtMargin:" + round4(tgtProfitMargin(s)));


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
        targetList.add(symb);
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
        if (costPerShare.containsKey(symb) && costPerShare.get(symb) != 0.0) {
            return price / costPerShare.get(symb);
        }
        return 1;
    }

    private static boolean checkDeltaImpact(String symb, double price) {
        double addition = getBuySize(symb, price).longValue() * price;

//        pr(symb, "check delta impact", "nowDelta+addition<TotalLimit:",
//                aggregateDelta + addition < DELTA_TOTAL_LIMIT,
//                "deltaStock+Inc<Limit:", symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
//                        getSizeFromPrice(price).longValue() * price < DELTA_EACH_LIMIT);

        return totalDelta + addition < DELTA_TOTAL_LIMIT &&
                (symbDelta.getOrDefault(symb, MAX_VALUE) +
                        addition < DELTA_LIMIT_EACH);
    }

    static double refillPx(String symb, double price, long pos, double costPerShare) {
        if (price == 0.0 || pos == 0.0 || costPerShare == 0.0) {
            return 0.0;
        }
        double currentCost = costPerShare * pos;
        double lowerTgt = reduceCostTgt(symb);
        double buySize = getBuySize(symb, price).longValue();
//        pr("calc refillPx: symb price pos buysize costbasis lowerTgt refillPx",
//                symb, price, pos, buySize, costPerShare, lowerTgt,
//                (costPerShare * lowerTgt * (pos + buySize) - currentCost) / buySize);

        return Math.min(price,
                (costPerShare * lowerTgt * (pos + buySize) - currentCost) / buySize);
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
        Decimal pos = symbPos.get(symb);

        if (oneDayP < 10 && twoDayP < 20 && checkDeltaImpact(symb, px)) {
            if (pos.isZero()) {
                outputToSymbol(symb, "*1st Buy*", t.format(MdHmmss),
                        "1dp:" + oneDayP, "2dp:" + twoDayP);
                inventoryAdder(ct, px, t, getBuySize(symb, px));
            } else if (pos.longValue() > 0 && costPerShare.getOrDefault(symb, 0.0) != 0.0) {
                if (px < refillPx(symb, px, pos.longValue(), costPerShare.get(symb))) {
                    outputToSymbol(symb, "*REFILL*", t.format(MdHmmss),
                            "deltaNow:" + symbDelta.getOrDefault(symb, 0.0),
                            "1dp:" + oneDayP, "2dp:" + twoDayP,
                            "avgCost:" + costPerShare.get(symb),
                            "px/cost:" + round4(pxOverCost(px, symb)),
                            "refillPx:" + (round2(refillPx(symb, px, pos.longValue(), costPerShare.get(symb)))),
                            "avgRng:" + avgDailyRng.getOrDefault(symb, 0.0));
                    inventoryAdder(ct, px, t, getBuySize(symb, px));
                }
            }
        } else if (oneDayP > 80 && pos.longValue() > 0) {
            double pOverCost = pxOverCost(px, symb);
            pr("px/Cost", symb, pxOverCost(px, symb));
            if (pOverCost > tgtProfitMargin(symb)) {
                outputToSymbol(symb, "****CUT****", t.format(MdHmmss),
                        "1dP:" + oneDayP, "2dp:" + twoDayP,
                        "px/Cost:" + round4(pOverCost),
                        "reqMargin:" + round4(tgtProfitMargin(symb)),
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
                lastPx.put(symb, price);
                liveData.get(symb).put(t, price);
                latestPriceTimeMap.put(symb, getESTDateTimeNow());

                if (twoDayData.get(symb).containsKey(t.truncatedTo(MINUTES))) {
                    twoDayData.get(symb).get(t.truncatedTo(MINUTES)).add(price);
                } else {
                    twoDayData.get(symb).put(t.truncatedTo(MINUTES), new SimpleBar(price));
                }
                if (symbPos.containsKey(symb)) {
                    symbDelta.put(symb, price * symbPos.get(symb).longValue());
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

        if (!contract.symbol().equals("USD") && targetList.contains(symb)) {
            symbPos.put(symb, position);
            Allstatic.costPerShare.put(symb, avgCost);
            outputToSymbol(symb, "updating position:", usDateTime(),
                    "position:" + position, "cost:" + round2(avgCost));
        }
    }

    @Override
    public void positionEnd() {
        pr(usDateTime(), "position end");
        targetList.forEach(symb -> {
            if (!symbPos.containsKey(symb)) {
                symbPos.put(symb, Decimal.ZERO);
            }

            outputToSymbol(symb, "POS COST", symbPos.get(symb).longValue(),
                    round2(costPerShare.getOrDefault(symb, 0.0)),
                    "DELTA:" + round2(symbPos.get(symb).longValue() * costPerShare.getOrDefault(symb, 0.0)));


            api.reqContractDetails(symbolContractMap.get(symb), list -> list.forEach(a ->
                    symbolContractIDMap.put(symb, a.contract().conid())));

            es.schedule(() -> {
                pr("Position end: requesting live:", symb);
                req1ContractLive(api, symbolContractMap.get(symb), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    private static void periodicCompute() {
        targetList.forEach(s -> {
            if (symbPos.containsKey(s)) {
                if (lastPx.getOrDefault(s, 0.0) != 0.0 && costPerShare.getOrDefault(s, 0.0) != 0.0) {
                    pr(s, usTime(), "pos:" + symbPos.get(s),
                            "px:" + lastPx.get(s),
                            "range:" + round4(avgDailyRng.getOrDefault(s, 0.0)),
                            "delta:" + round1(symbPos.get(s).longValue() * lastPx.get(s)),
                            "cost:" + round2(costPerShare.get(s)),
                            "rtn:" + round(1000.0 * (lastPx.get(s) / costPerShare.get(s) - 1)) / 10.0 + "%",
                            "buySize:" + getBuySize(s, lastPx.get(s)),
                            "lowerCostTgt:" + round3(reduceCostTgt(s)),
                            "refillPx:" + round2(refillPx(s, lastPx.get(s)
                                    , symbPos.get(s).longValue(), costPerShare.get(s))),
                            "refillPx/Cost:" + round3(refillPx(s, lastPx.get(s)
                                    , symbPos.get(s).longValue(), costPerShare.get(s)) / costPerShare.get(s)),
                            "refillPx/Px:" + round3(refillPx(s, lastPx.get(s)
                                    , symbPos.get(s).longValue(), costPerShare.get(s))
                                    / lastPx.get(s)),
                            "1dp:" + oneDayPctMap.getOrDefault(s, 0.0),
                            "2dp:" + twoDayPctMap.getOrDefault(s, 0.0));
                }
            }
        });

        targetList.forEach(symb -> {
            if (twoDayData.containsKey(symb) && !twoDayData.get(symb).isEmpty()) {
                double twoDayPercentile = calculatePercentileFromMap(twoDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(twoDayData.get(symb)
                        .tailMap(TODAY230));

                twoDayPctMap.put(symb, twoDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
            }
        });

        totalDelta = targetList.stream().mapToDouble(s ->
                symbPos.getOrDefault(s, Decimal.ZERO).longValue() * lastPx.getOrDefault(s, 0.0)).sum();

        targetList.forEach((s) -> symbDelta.put(s, (double) round(symbPos.getOrDefault(s, Decimal.ZERO)
                .longValue() * lastPx.getOrDefault(s, 0.0))));

        pr("aggregate Delta", r(totalDelta), symbDelta);

        openOrders.forEach((k, v) -> v.forEach((k1, v1) -> {
            if (orderStatus.get(k).get(k1).isFinished()) {
                outputToSymbol(k, "in compute: removing finished orders", "ordID:" +
                        k1, "order:" + v1);
                v.remove(k1);
            }
        }));
    }

    private static void inventoryAdder(Contract ct, double px, LocalDateTime t, Decimal buySize) {
        String s = ibContractToSymbol(ct);

        if (symbDelta.getOrDefault(s, MAX_VALUE) + buySize.longValue() * px > deltaLimitEach(s)) {
            outputToSymbol(s, usDateTime(), "buy exceeds lmt. deltaNow:" + symbDelta.getOrDefault(s, MAX_VALUE),
                    "addDelta:" + buySize.longValue() * px);
            return;
        }
        int id = tradID.incrementAndGet();
        double bidPx = r(Math.min(px, bidMap.getOrDefault(s, px)));
        Order o = placeBidLimitTIF(id, bidPx, buySize, DAY);
        orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_ADDER));
        orderStatus.get(s).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
        outputToSymbol(s, "ordID:" + o.orderId(), "tradID:" + id, o.action(),
                "px:" + bidPx, "buySize:" + buySize, orderSubmitted.get(s).get(o.orderId()));
        outputToSymbol(s, "2DStats:" + genStatsString(twoDayData.get(s)),
                "1DStats:" + genStatsString(twoDayData.get(s).tailMap(TODAY230)));
    }

    private static void inventoryCutter(Contract ct, double px, LocalDateTime t) {
        String s = ibContractToSymbol(ct);
        Decimal pos = symbPos.get(s);

        int id = tradID.incrementAndGet();
        double cost = costPerShare.getOrDefault(s, MAX_VALUE);
        double offerPrice = r(Math.max(askMap.getOrDefault(s, px),
                costPerShare.getOrDefault(s, MAX_VALUE) * tgtProfitMargin(s)));

        Order o = placeOfferLimitTIF(id, offerPrice, pos, DAY);
        orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
        orderStatus.get(s).put(o.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
        outputToSymbol(s, "ordID:" + o.orderId(), "tradID:" + id, o.action(), "Px:" + offerPrice,
                "qty:" + o.totalQuantity().longValue(), "cost:" + round2(cost),
                orderSubmitted.get(s).get(o.orderId()),
                "reqMargin:" + tgtProfitMargin(s),
                "tgtSellPx:" + round2(cost * tgtProfitMargin(s)),
                "askPx:" + askMap.getOrDefault(s, 0.0));
        outputToSymbol(s, "2DStats:" + genStatsString(twoDayData.get(s)));
        outputToSymbol(s, "1DStats:" + genStatsString(twoDayData.get(s).tailMap(TODAY230)));
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
            outputToSymbol(s, usDateTime(), "*openOrder*:aftr removal. openOrders:", openOrders.get(s));
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
                "fillPx:" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        String s = findSymbolByID(orderId);
        if (s.equalsIgnoreCase("")) {
            outputToError("*orderStatus* orderID not found:", orderId);
            return;
        }

        outputToSymbol(s, usDateTime(), "*OrderStatus*:" + status,
                "orderId:" + orderId, "filled:" + filled, "remaining:" + remaining,
                "fillPx" + avgFillPrice, "lastFillPx:" + lastFillPrice);

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
        outputToError("*openOrder* Error", usDateTime(), "orderId:" +
                orderId, "errorCode:" + errorCode, "msg:" + errorMsg);
    }

    //request realized pnl
    //Execution details *****************
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        String s = ibContractToSymbol(contract);

        if (s.equalsIgnoreCase("USD")) {
            return;
        }

        outputToSymbol(s, usDateTime(), "*tradeReport* time:",
                executionToUSTime(execution.time()), execution.side(),
                "execPx:" + execution.price(), "shares:" + execution.shares(),
                "avgPx:" + execution.avgPrice());

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
        if (!tradeKeyExecutionMap.containsKey(tradeKey)
                || tradeKeyExecutionMap.get(tradeKey).isEmpty()) {
            return;
        }

        String s = tradeKeyExecutionMap.get(tradeKey).get(0).getSymbol();

        if (orderSubmitted.containsKey(s) && !orderSubmitted.get(s).isEmpty()) {
            orderSubmitted.get(s).entrySet().stream().filter(e1 -> e1.getValue().getOrder().orderId()
                            == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId())
                    .forEach(e2 -> {
                        String outp = str("1.*commission report* orderID:" + e2.getKey(),
                                "commission:" + commissionReport.commission(),
                                e2.getValue().getOrder().action() == SELL ?
                                        str("orderID:", e2.getKey(), "realized pnl:",
                                                commissionReport.realizedPNL()) : "");
                        outputToSymbol(s, outp);
                        outputToFills(s, outp);
                    });

            orderSubmitted.get(s).forEach((key1, value1) -> {
                if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId()) {
                    outputToSymbol(s, "2.*commission report* orderID:" + value1.getOrder().orderId(),
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
            targetList.forEach(s -> {
                outputToSymbol(s, "*Periodic Run*", usDateTime());
                outputToSymbol(s,
                        latestPriceTimeMap.containsKey(s) ?
                                str("last Live feed time:", latestPriceTimeMap.get(s).format(MdHmm)
                                        , "px:" + lastPx.getOrDefault(s, 0.0),
                                        costPerShare.getOrDefault(s, 0.0) == 0.0 ? "" : str(
                                                "cost:" + round2(costPerShare.get(s)),
                                                "px/cost:" + round4(lastPx.getOrDefault(s, 0.0)
                                                        / costPerShare.getOrDefault(s, 0.0)))) :
                                str("no live feed"));
//                outputToSymbol(s, "delta::" + symbDelta.getOrDefault(s, 0.0));
                if (symbDelta.getOrDefault(s, 0.0) > 0.0 && costPerShare.getOrDefault(s, 0.0) != 0.0) {
                    outputToSymbol(s, "px:" + lastPx.getOrDefault(s, 0.0),
                            "avgRange:" + round(10000.0 * avgDailyRng.getOrDefault(s, 0.0)) / 100.0 + "%",
                            "delta:" + round1(symbDelta.getOrDefault(s, 0.0)),
                            "pos:" + symbPos.getOrDefault(s, Decimal.ZERO).longValue(),
                            "cost:" + round2(costPerShare.get(s)),
                            "buySize:" + getBuySize(s, lastPx.get(s)),
                            "refillPx:" + round2(refillPx(s, lastPx.get(s),
                                    symbPos.get(s).longValue(), costPerShare.get(s))),
                            "costTgt%:" + round5(reduceCostTgt(s)),
                            "refillPx/cost:" + round3(refillPx(s, lastPx.get(s),
                                    symbPos.get(s).longValue()
                                    , costPerShare.get(s)) / costPerShare.get(s)),
                            "refillPx/px:" + round3(refillPx(s, lastPx.get(s),
                                    symbPos.get(s).longValue()
                                    , costPerShare.get(s)) / lastPx.get(s)));
                }
                if (!orderStatus.get(s).isEmpty()) {
                    outputToSymbol(s, usDateTime(), "*chek orderStatus", orderStatus.get(s));
                }
                if (!openOrders.get(s).isEmpty()) {
                    outputToSymbol(s, usDateTime(), "*chek openOrders*:", openOrders.get(s));
                }
                outputToSymbol(s, usDateTime(), "2dP:" + twoDayPctMap.getOrDefault(s, 0.0),
                        "1dP:" + oneDayPctMap.getOrDefault(s, 0.0));
                outputToSymbol(s, "*2dStats:" + genStatsString(twoDayData.get(s)));
                outputToSymbol(s, "*1dStats:" + genStatsString(twoDayData.get(s).tailMap(TODAY230)));
            });
        }, 20L, 3600L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> outputToGeneral("*Ending*", usDateTime())));
    }
}
