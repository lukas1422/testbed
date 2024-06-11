package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import controller.AccountSummaryTag;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static Trader.Allstatic.*;
import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.OrderStatus.Created;
import static client.OrderStatus.Filled;
import static client.Types.Action.BUY;
import static client.Types.Action.SELL;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static Trader.TradingUtility.*;
import static java.lang.Double.MAX_VALUE;
import static java.lang.Math.round;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.stream.Collectors.*;
import static utility.Utility.*;

class ProfitTargetTrader implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler
        , ApiController.IAccountSummaryHandler {

    private static volatile double AVAILABLE_CASH = 0.0;
    private static final double DELTA_TOTAL_LIMIT = 286000;
    private static final double DELTA_LIMIT_EACH = DELTA_TOTAL_LIMIT / 3.0;
    private static final double CURRENT_REFILL_N = 3.0; //refill times now due to limited delta
    private static final double IDEAL_REFILL_N = 20.0; //ideally how many times to refill
    private static final double MAX_DRAWDOWN_TARGET = 0.8;
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData
            = new ConcurrentSkipListMap<>();
    private static volatile Map<String, Double> lastYearCloseMap = new ConcurrentHashMap<>();
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>>
            twoDayData = new ConcurrentSkipListMap<>(String::compareTo);
    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>> orderSubmitted
            = new ConcurrentHashMap<>();
    //    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>>
//            orderStatus = new ConcurrentHashMap<>();
    private static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>>
            openOrders = new ConcurrentSkipListMap<>();
    //data
    private volatile static Map<String, Double> px = new ConcurrentHashMap<>();
    private static Map<String, LocalDateTime> lastPxTimestamp = new ConcurrentHashMap<>();
    private volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();
    private volatile static Map<String, Decimal> symbPos = new ConcurrentSkipListMap<>(String::compareTo);
    private volatile static Map<String, Double> symbDelta = new ConcurrentSkipListMap<>(String::compareTo);
    private static Map<String, Double> twoDayPctMap = new ConcurrentHashMap<>();
    private static Map<String, Double> oneDayPctMap = new ConcurrentHashMap<>();
    private static Map<String, Integer> symbolContractIDMap = new ConcurrentHashMap<>();
    private static Map<String, List<ExecutionAugmented>> tradeKeyExecutionMap = new ConcurrentHashMap<>();
    //historical data
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>> ytdDayData
            = new ConcurrentSkipListMap<>(String::compareTo);
    private static ConcurrentSkipListMap<String, Double> ytdReturn = new ConcurrentSkipListMap<>();
    private static volatile double totalDelta = 0.0;
    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private static ApiController api;
    private static volatile TreeSet<String> targets = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();
    private static final int MASTERID = getSessionMasterTradeID();
    private static volatile AtomicInteger tradID = new AtomicInteger(MASTERID + 1);

    private static final int GATEWAY_PORT = 4001;
    private static final int TWS_PORT = 7496;
    private static final int PORT_TO_USE = GATEWAY_PORT;

    private static Map<String, Double> rng = new HashMap<>();

    private ProfitTargetTrader() throws IOException {
        outputToGeneral("*****START***** HKT:", hkTime(), "EST:", usDateTime(), "MASTERID:", MASTERID);
        pr("mkt start time today:", TODAY930);
        pr("costTgt", Math.pow(MAX_DRAWDOWN_TARGET, 1 / (IDEAL_REFILL_N - 1)));
        pr("until mkt start time:", Duration.between(TODAY930, getESTDateTimeNow()).toMinutes(), "mins");

        Files.lines(Paths.get(RELATIVEPATH + "interestListUS")).map(l -> l.split(" "))
                .forEach(a -> {
                    //pr("whole line", a);
                    pr("a[0]", a[0]);
                    String stockName = a[0].equalsIgnoreCase("BRK") ? "BRK B" : a[0];
                    registerContract(generateUSStockContract(stockName));
                });
    }

    private static void ytdOpen(Contract c, String date, double open, double high,
                                double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);

        if (!ytdDayData.containsKey(symbol)) {
            ytdDayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
        ytdDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
    }

    private static double deltaLimitEach(String s) {
//        return s.equalsIgnoreCase("SPY") ? ProfitTargetTrader.DELTA_TOTAL_LIMIT / 4 :
//                ProfitTargetTrader.DELTA_LIMIT_EACH;
        return DELTA_TOTAL_LIMIT / 4.0;
    }

    private static Decimal getLot(String symb, double price) {
        return Decimal.get(Math.max(0, Math.floor(deltaLimitEach(symb) /
                price / CURRENT_REFILL_N)));
    }

    private static double costTgt(String symb) {
        return mins(symb.equalsIgnoreCase("SPY") ? 0.99 : 0.97,
                1 - rng.getOrDefault(symb, 0.0),
                Math.pow(MAX_DRAWDOWN_TARGET, 1 / (IDEAL_REFILL_N - 1)));
    }

    private static void todaySoFar(Contract c, String date, double open,
                                   double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);
        LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
                TimeZone.getTimeZone("America/New_York").toZoneId());

        twoDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
        liveData.get(symbol).put(ld, close);
    }

    private static double tgtProfitMargin(String s) {
        return Math.max(minProfitMargin(s), 1 + rng.getOrDefault(s, 0.0) * 0.85);
    }

    private void connectAndReqPos() {
        api = new ApiController(new DefaultConnectionHandler(),
                new DefaultLogger(), new DefaultLogger());
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
            targets.forEach(symb -> {
                Contract c = symbolContractMap.get(symb);
                if (!twoDayData.containsKey(symb)) {
                    twoDayData.put(symb, new ConcurrentSkipListMap<>());
                }
                pr("requesting hist day data", symb);
                CompletableFuture.runAsync(() -> reqHistDayData(api, Allstatic.ibStockReqId.addAndGet(5),
                        c, ProfitTargetTrader::todaySoFar, () ->
                                pr(symb, "2D$:" + genStats(twoDayData.get(symb)),
                                        "1D$:" + genStats(twoDayData.get(symb).tailMap(TODAY230))),
                        2, Types.BarSize._1_min));
                CompletableFuture.runAsync(() -> reqHistDayData(api, Allstatic.ibStockReqId.addAndGet(5),
                        c, ProfitTargetTrader::ytdOpen, () -> computeHistoricalData(symb)
                        , Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day));
            });
            pr("print all target stocks:", targets);
            api.reqPositions(this);
            api.reqLiveOrders(this);
            AccountSummaryTag[] tags =
                    {AccountSummaryTag.AvailableFunds};
            api.reqAccountSummary("All", tags, this);

            pr("req Executions");
            api.reqExecutions(new ExecutionFilter(), this);
            outputToGeneral(usDateTime(), "cancelling all orders on start up");
            api.cancelAllOrders();
        }, 2, TimeUnit.SECONDS);
    }

    private static void computeHistoricalData(String s) {
        if (ytdDayData.containsKey(s) && !ytdDayData.get(s).isEmpty()) {
            double rng = ytdDayData.get(s).values().stream().mapToDouble(SimpleBar::getHLRange)
                    .average().orElse(0.0);
            ProfitTargetTrader.rng.put(s, rng);
            outputToSymbol(s, usDateTime(), "rng:" + round(rng * 1000.0) / 10.0 + "%",
                    "costTgt:" + round3(costTgt(s)));
            outputToSymbol(s, usDateTime(), "tgtMargin:" + round4(tgtProfitMargin(s))
                    , "tgtPrice:" + round2(costMap.getOrDefault(s, 0.0) * tgtProfitMargin(s)));

            if (ytdDayData.get(s).firstKey().isBefore(getYearBeginMinus1Day())) {
                double lastYearClose = ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                lastYearCloseMap.put(s, lastYearClose);
                ytdReturn.put(s, ytdDayData.get(s).lastEntry().getValue().getClose() / lastYearClose - 1);
                outputToSymbol(s, "ytdReturn:" + round(ytdReturn.get(s) * 10000d) / 100d + "%");
                pr("last year close for", s, ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getKey(),
                        ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose(),
                        "YTD:", round2(ytdReturn.get(s)));
            }
        } else {
            pr("no historical data to compute ", s);
        }
    }

//    private static void registerContractAll(Contract... cts) {
//        Arrays.stream(cts).forEach(ProfitTargetTrader::registerContract);
//    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        outputToSymbol(symb, "*******************************************");
        outputToSymbol(symb, "*STARTS*", usDateTime());
        symbolContractMap.put(symb, ct);
        targets.add(symb);
        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
//        orderStatus.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
        if (!ytdDayData.containsKey(symb)) {
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    private static boolean noBlockingBuyOrders(String s) {
        if (orderSubmitted.get(s).isEmpty()) {
            return true;
        }
        outputToSymbol(s, "no blocking buy orders orderStatus nonempty:"
                , orderSubmitted.get(s));

        if (orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
                .allMatch(OrderStatus::isFinished)) {
            outputToSymbol(s, "all orders finished", orderSubmitted.get(s));
            return true;
        } else {
            outputToSymbol(s, "some buy orders are not finished:", orderSubmitted.get(s));
            return orderSubmitted.get(s).entrySet().stream()
                    .filter(e -> !e.getValue().getOrderStatus().isFinished())
                    .noneMatch(e -> e.getValue().getOrder().action() == BUY);
        }
    }

    private static boolean noBlockingSellOrders(String s) {
        if (orderSubmitted.get(s).isEmpty()) {
            outputToSymbol(s, "orderstatus empty");
            return true;
        }
        pr(s, "no blocking sell orders check orderSubmitted:", orderSubmitted.get(s));

        if (orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
                .allMatch(OrderStatus::isFinished)) {
            outputToSymbol(s, "allorderfinished:", orderSubmitted.get(s));
            return true;
        } else {
            outputToSymbol(s, "some sell orders are not finished:", orderSubmitted.get(s));

            return orderSubmitted.get(s).entrySet()
                    .stream().filter(e -> !e.getValue().getOrderStatus().isFinished())
                    .noneMatch(e -> orderSubmitted.get(s).get(e.getKey()).getOrder()
                            .action() == SELL);
        }
    }


    private static boolean noBlockingOrders(String s) {
        if (!orderSubmitted.get(s).isEmpty()) {
            pr(s, "no blocking orders check:", orderSubmitted.get(s));
        }
        return orderSubmitted.get(s).isEmpty() ||
                orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
                        .allMatch(OrderStatus::isFinished);
    }

    private static double pxOverCost(double price, String symb) {
        if (costMap.containsKey(symb) && costMap.get(symb) != 0.0) {
            return price / costMap.get(symb);
        }
        return 1;
    }

    private static boolean checkDeltaImpact(String symb, double price) {
        double addition = getLot(symb, price).longValue() * price;

//        pr(symb, "check delta impact", "nowDelta+addition<TotalLimit:",
//                aggregateDelta + addition < DELTA_TOTAL_LIMIT,
//                "deltaStock+Inc<Limit:", symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
//                        getSizeFromPrice(price).longValue() * price < DELTA_EACH_LIMIT);

        return addition < AVAILABLE_CASH && totalDelta + addition < DELTA_TOTAL_LIMIT &&
                (symbDelta.getOrDefault(symb, MAX_VALUE) +
                        addition < DELTA_LIMIT_EACH);
    }

    private static double refillPx(String symb, double px, long pos, double costPerShare) {
        if (px <= 0.0 || pos <= 0.0 || costPerShare <= 0.0) {
            return 0.0;
        }
        double currentCostBasis = costPerShare * pos;
        double lowerTgt = costTgt(symb);
        double buySize = getLot(symb, px).longValue();
//        pr("calc refillPx: symb price pos buysize costbasis lowerTgt refillPx",
//                symb, price, pos, buySize, costPerShare, lowerTgt,
//                (costPerShare * lowerTgt * (pos + buySize) - currentCostBasis) / buySize);

        return Math.min(costPerShare,
                (costPerShare * lowerTgt * (pos + buySize) - currentCostBasis) / buySize);
    }

    private static void tryToTrade(Contract ct, double px, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            return;
        }
        String s = ibContractToSymbol(ct);
//        if (!noBlockingOrders(s)) {
//            outputToSymbol(s, t.format(Hmmss), "order blocked by:" +
//                    openOrders.get(s).values(), "orderStatus:" + orderStatus.get(s));
//            return;
//        }

        if (!ct.currency().equalsIgnoreCase("USD")) {
            outputToGeneral(usDateTime(), "only USD stock allowed, s:", ct.symbol());
            return;
        }

        if (!twoDayPctMap.containsKey(s) || !oneDayPctMap.containsKey(s)) {
            pr(s, "no percentile info:", !twoDayPctMap.containsKey(s) ? "2day" : "",
                    !oneDayPctMap.containsKey(s) ? "1day" : "");
            return;
        }

        double twoDayP = twoDayPctMap.get(s);
        double oneDayP = oneDayPctMap.get(s);
        Decimal pos = symbPos.get(s);

        if (oneDayP < 10 && twoDayP < 20 && checkDeltaImpact(s, px)) {
            if (!noBlockingBuyOrders(s)) {
                outputToSymbol(s, t.format(Hmmss), "buy order blocked by:" +
                        openOrders.get(s).values(), "orderStatus:" + orderSubmitted.get(s));
                return;
            }

            if (pos.isZero()) {
                outputToSymbol(s, "*1Buy*", t.format(MdHmmss), "1dp:" + oneDayP, "2dp:" + twoDayP);
                inventoryAdder(ct, px, t, getLot(s, px));
            } else if (pos.longValue() > 0 && costMap.getOrDefault(s, 0.0) != 0.0) {
                if (px < refillPx(s, px, pos.longValue(), costMap.get(s))) {
                    outputToSymbol(s, "*REFILL*", t.format(MdHmmss),
                            "delta:" + round(symbDelta.getOrDefault(s, 0.0) / 1000.0) + "k",
                            "1dp:" + oneDayP, "2dp:" + twoDayP,
                            "cost:" + round1(costMap.get(s)),
                            "px/cost:" + round4(pxOverCost(px, s)),
                            "refilPx:" + (round2(refillPx(s, px, pos.longValue(), costMap.get(s)))),
                            "avgRng:" + round4(rng.getOrDefault(s, 0.0)));
                    inventoryAdder(ct, px, t, getLot(s, px));
                }
            }
        }

        if (pos.longValue() > 0) {

            double pOverCost = pxOverCost(px, s);
            if (pOverCost > tgtProfitMargin(s)) {
                if (!noBlockingOrders(s)) {
                    outputToSymbol(s, t.format(Hmmss), "sell order blocked by:" +
                            openOrders.get(s).values(), "orderStatus:" + orderSubmitted.get(s));
                    return;
                }

                outputToSymbol(s, "****CUT**", t.format(MdHmmss),
                        "1dP:" + oneDayP, "2dp:" + twoDayP,
                        "px/Cost:" + round4(pOverCost),
                        "reqMargin:" + round4(tgtProfitMargin(s)),
                        "rng:" + round4(rng.getOrDefault(s, 0.0)));
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
                pr(t.format(Hmmss), "last p:", symb, price);
                px.put(symb, price);
                liveData.get(symb).put(t, price);
                lastPxTimestamp.put(symb, getESTDateTimeNow());

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
        String s = ibContractToSymbol(contract);

        if (!contract.symbol().equals("USD") && targets.contains(s)) {
            symbPos.put(s, position);
            costMap.put(s, avgCost);
            outputToSymbol(s, "updating pos:", usDateTime(), "pos:" + position, "cost:" + round2(avgCost));
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

    private static void periodicCompute() {
        targets.forEach(s -> {
            if (symbPos.containsKey(s)) {
                if (px.getOrDefault(s, 0.0) != 0.0 && costMap.getOrDefault(s, 0.0) != 0.0) {
                    pr(s, "pos:" + symbPos.get(s),
                            "p:" + px.get(s),
                            "1dp:" + (oneDayPctMap.containsKey(s) ? round(oneDayPctMap.get(s)) : "n/a"),
                            "2dp:" + (twoDayPctMap.containsKey(s) ? round(twoDayPctMap.get(s)) : "n/a"),
                            "delt:" + round(symbPos.get(s).longValue() * px.get(s) / 1000.0) + "k",
                            "cost:" + round1(costMap.get(s)),
                            "rtn:" + round(1000.0 * (px.get(s) / costMap.get(s) - 1)) / 10.0 + "%",
                            "#:" + getLot(s, px.get(s)),


                            "costTgt:" + round2(costTgt(s)),
                            "refil@" + round1(refillPx(s, px.get(s), symbPos.get(s).longValue(), costMap.get(s))),
                            "refil/Cost:" + round2(refillPx(s, px.get(s), symbPos.get(s).longValue(), costMap.get(s)) /
                                    costMap.get(s)),
                            "refil/Px:" + round2(refillPx(s, px.get(s)
                                    , symbPos.get(s).longValue(), costMap.get(s)) / px.get(s)),
                            "rng:" + round(1000.0 * rng.getOrDefault(s, 0.0)) / 10.0 + "%",
                            "tgtMargin:" + round4(tgtProfitMargin(s)),
                            "tgtPx:" + round2(costMap.get(s) * tgtProfitMargin(s)));
                }
            }
        });

        targets.forEach(s -> {
            if (twoDayData.containsKey(s) && !twoDayData.get(s).isEmpty()) {
                double twoDayP = computePtile(twoDayData.get(s));
                double oneDayP = computePtile(twoDayData.get(s).tailMap(TODAY230));
                twoDayPctMap.put(s, twoDayP);
                oneDayPctMap.put(s, oneDayP);
                pr(usTime(), s, "1DP:" + oneDayP, "2DP:" + twoDayP);
            }
        });
        totalDelta = targets.stream().mapToDouble(s ->
                symbPos.getOrDefault(s, Decimal.ZERO).longValue()
                        * px.getOrDefault(s, 0.0)).sum();

        targets.forEach((s) -> symbDelta.put(s, (double) round(symbPos.getOrDefault(s, Decimal.ZERO)
                .longValue() * px.getOrDefault(s, 0.0))));

        pr(usTime(), "Delt:" + round(totalDelta / 1000.0) + "k",
                symbDelta.entrySet().stream().sorted((Map.Entry.<String, Double>comparingByValue().reversed()))
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                e -> round(e.getValue() / 1000.0) + "k",
                                (a, b) -> a, LinkedHashMap::new)));

        openOrders.forEach((k, v) -> v.forEach((k1, v1) -> {
            if (orderSubmitted.get(k).get(k1).getOrderStatus().isFinished()) {
                outputToSymbol(k, "in compute: removing finished orders", "ordID:" +
                        k1, "order:" + v1);
                v.remove(k1);
            }
        }));
    }

    private static void inventoryAdder(Contract ct, double px, LocalDateTime t, Decimal lotSize) {
        String s = ibContractToSymbol(ct);

        if (!ytdReturn.containsKey(s)) {
            outputToSymbol(s, "ytdReturn not available, quitting inventoryadder");
            return;
        }

        if (ytdReturn.get(s) < -0.1) {
            outputToSymbol(s, "Adder: ytd < -10% cannot trade:", ytdReturn.get(s));
            return;
        }

        if (symbDelta.getOrDefault(s, MAX_VALUE) + lotSize.longValue() * px > deltaLimitEach(s)) {
            outputToSymbol(s, usDateTime(), "buy exceeds lmt. deltaNow:" +
                            symbDelta.getOrDefault(s, MAX_VALUE),
                    "addDelta:" + lotSize.longValue() * px);
            return;
        }

        int id1 = tradID.incrementAndGet();
        double bidPx1 = r(Math.min(px, bidMap.getOrDefault(s, px)));
        Decimal size1 = Decimal.get(round(lotSize.longValue() * 4.0 / 5.0));

        Order o1 = placeBidLimitTIF(id1, bidPx1, size1, DAY);
        orderSubmitted.get(s).put(o1.orderId(), new OrderAugmented(ct, t, o1, INVENTORY_ADDER, Created));
//        orderStatus.get(s).put(o1.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o1, new OrderHandler(s, o1.orderId()));
        outputToSymbol(s, "ordID1:" + o1.orderId(), "tradID1:" + id1, o1.action(),
                "px1:" + bidPx1, "lot1:" + size1, orderSubmitted.get(s).get(o1.orderId()));

        //second order, reduce cost by 20 bps
        int id2 = tradID.incrementAndGet();
        double bidPx2 = r(Math.min(px, bidMap.getOrDefault(s, px) * 0.998));
        Decimal size2 = Decimal.get(round(lotSize.longValue() / 5.0));
        Order o2 = placeBidLimitTIF(id2, bidPx2, size2, DAY);
        orderSubmitted.get(s).put(o2.orderId(), new OrderAugmented(ct, t, o2, INVENTORY_ADDER, Created));
//        orderStatus.get(s).put(o2.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o2, new OrderHandler(s, o2.orderId()));
        outputToSymbol(s, "ordID2:" + o2.orderId(), "tradID2:" + id2, o2.action(),
                "px2:" + bidPx2, "lot2:" + size2, orderSubmitted.get(s).get(o2.orderId()));

        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
    }

    private static void inventoryCutter(Contract ct, double px, LocalDateTime t) {
        String s = ibContractToSymbol(ct);
        Decimal pos = symbPos.get(s);

        int id1 = tradID.incrementAndGet();
        double cost = costMap.getOrDefault(s, MAX_VALUE);
        double offerPrice = r(Math.max(askMap.getOrDefault(s, px),
                costMap.getOrDefault(s, MAX_VALUE) * tgtProfitMargin(s)));

//        Decimal sellQ1 = Decimal.get(round(pos.longValue() * 4.0 / 5.0));
//        Decimal sellQ2 = Decimal.get(pos.longValue() - sellQ1.longValue());

        Order o1 = placeOfferLimitTIF(id1, offerPrice, pos, DAY);
        orderSubmitted.get(s).put(o1.orderId(), new OrderAugmented(ct, t, o1, INVENTORY_CUTTER, Created));
//        orderStatus.get(s).put(o1.orderId(), OrderStatus.Created);
        placeOrModifyOrderCheck(api, ct, o1, new OrderHandler(s, o1.orderId()));
        outputToSymbol(s, "ordID1:" + o1.orderId(), "tradID1:" + id1, o1.action(), "px1:" + offerPrice,
                "q1:" + o1.totalQuantity().longValue(), "cost:" + round2(cost));

//        int id2 = tradID.incrementAndGet();
//        Order o2 = placeOfferLimitTIF(id2, r(offerPrice * 1.002), sellQ2, DAY);
//        orderSubmitted.get(s).put(o2.orderId(), new OrderAugmented(ct, t, o2, INVENTORY_CUTTER));
//        orderStatus.get(s).put(o2.orderId(), OrderStatus.Created);
//        placeOrModifyOrderCheck(api, ct, o2, new OrderHandler(s, o2.orderId()));
//        outputToSymbol(s, "ordID2:" + o2.orderId(), "tradID2:" + id2, o2.action(),
//                "px2:", offerPrice * 1.002,
//                "q1:" + o2.totalQuantity().longValue(), "cost:" + round2(cost));

        outputToSymbol(s, "sell:", orderSubmitted.get(s).get(o1.orderId()),
                "reqMargin:" + round5(tgtProfitMargin(s)),
                "tgtSellPx:" + round2(cost * tgtProfitMargin(s)),
                "askPx:" + askMap.getOrDefault(s, 0.0));
//        outputToSymbol(s, "sell part2:", orderSubmitted.get(s).get(o2.orderId()),
//                "tgtSellPx:" + round2(cost * tgtProfitMargin(s) * 1.002));

        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
    }

    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String s = ibContractToSymbol(contract);

        if (!targets.contains(s)) {
            outputToSymbol(s, "not in profit target trader");
            return;
        }

        outputToSymbol(s, usDateTime(), "*openOrder* status:" + orderState.status(), order);
//        orderStatus.get(s).put(order.orderId(), orderState.status());
        orderSubmitted.get(s).get(order.orderId()).updateOrderStatus(orderState.status());

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
        outputToGeneral(usDateTime(), "*openOrderEnd*:print all openOrdrs Profit Target", openOrders,
                "orderSubmitted map:", orderSubmitted);
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
            outputToError("*orderStatus* orderID not found in ProfitTarget:", orderId);
            return;
        }

        outputToSymbol(s, usDateTime(), "*OrderStatus*:" + status,
                "orderId:" + orderId, "filled:" + filled, "remaining:" + remaining,
                "fillPx:" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        if (status == Filled) {
            outputToFills(s, usDateTime(), "*OrderStatus*: filled. ordID:" + orderId);
        }

        //put status in orderstatusmap
//        orderStatus.get(s).put(orderId, status);
        orderSubmitted.get(s).get(orderId).updateOrderStatus(status);
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
        for (String k : orderSubmitted.keySet()) {
            if (orderSubmitted.get(k).containsKey(id)) {
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
                                "commission:" + round2(commissionReport.commission()),
                                e2.getValue().getOrder().action() == SELL ?
                                        str("orderID:", e2.getKey(), "realized pnl:",
                                                round2(commissionReport.realizedPNL())) : "");
                        outputToSymbol(s, outp);
                        outputToFills(s, outp);
                    });

            orderSubmitted.get(s).forEach((key1, value1) -> {
                if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).get(0).getExec().orderId()) {
                    outputToSymbol(s, "2.*commission report* orderID:" + value1.getOrder().orderId(),
                            "commission:", round2(commissionReport.commission()),
                            value1.getOrder().action() == SELL ?
                                    str("realized pnl:", round2(commissionReport.realizedPNL()))
                                    : "");
                }
            });
        }

    }

//    public static ConcurrentSkipListMap<Integer, OrderAugmented> returnOrderSubmitted(String s) {
//        if (orderSubmitted.containsKey(s)) {
//            return orderSubmitted.get(s);
//        }
//        throw new UnsupportedOperationException("order submitted does not contain");
//    }


    //Execution end*********************************
//open orders end **********************
    public static void main(String[] args) throws IOException {
        ProfitTargetTrader test1 = new ProfitTargetTrader();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCompute, 20L, 10L, TimeUnit.SECONDS);
        es.scheduleAtFixedRate(() -> {
            targets.forEach(s -> {
                outputToSymbol(s, "*Periodic Run*", usTime());
                outputToSymbol(s, lastPxTimestamp.containsKey(s) ?
                        str("last live feed time:", lastPxTimestamp.get(s).format(MdHmm),
                                "been:" + Duration.between(lastPxTimestamp.get(s), getESTDateTimeNow()).toMinutes()
                                        + "mins.", "p:" + px.getOrDefault(s, 0.0),
                                costMap.getOrDefault(s, 0.0) == 0.0 ? "" : str(
                                        "cost:" + round1(costMap.get(s)),
                                        "p/cost:" + round3(px.getOrDefault(s, 0.0)
                                                / costMap.getOrDefault(s, 0.0)))) :
                        str("no live feed"));
                if (symbDelta.getOrDefault(s, 0.0) > 0.0 && costMap.getOrDefault(s, 0.0) != 0.0) {
                    outputToSymbol(s, "p:" + px.getOrDefault(s, 0.0),
                            "rng:" + round(1000.0 * rng.getOrDefault(s, 0.0)) / 10.0 + "%",
                            "pos:" + symbPos.getOrDefault(s, Decimal.ZERO).longValue(),
                            "delt:" + round(symbDelta.getOrDefault(s, 0.0) / 1000.0) + "k",
                            "cost:" + round1(costMap.get(s)),
                            "lot:" + getLot(s, px.get(s)),
                            "fillP:" + round2(refillPx(s, px.get(s), symbPos.get(s).longValue(), costMap.get(s))),
                            "costTgt:" + round3(costTgt(s)),
                            "fillP/cost:" + round3(refillPx(s, px.get(s),
                                    symbPos.get(s).longValue(), costMap.get(s)) / costMap.get(s)),
                            "fillP/px:" + round3(refillPx(s, px.get(s), symbPos.get(s).longValue()
                                    , costMap.get(s)) / px.get(s)));
                }
                if (!orderSubmitted.get(s).isEmpty()) {
                    outputToSymbol(s, usDateTime(), "*chek orderStatus", orderSubmitted.get(s));
                }
                if (!openOrders.get(s).isEmpty()) {
                    outputToSymbol(s, usDateTime(), "*chek openOrders*:", openOrders.get(s));
                }
                outputToSymbol(s, usDateTime(), "2dP:" + twoDayPctMap.getOrDefault(s, 101.0),
                        "1dP:" + oneDayPctMap.getOrDefault(s, 101.0));
                outputToSymbol(s, "2d$:" + genStats(twoDayData.get(s)));
                outputToSymbol(s, "1d$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
            });
        }, 20L, 3600L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> outputToGeneral("*Ending*", usDateTime())));
    }

    @Override
    public void accountSummary(String account, AccountSummaryTag tag, String value, String currency) {
        pr("account summary", account, tag, value, currency);
        if (tag == AccountSummaryTag.AvailableFunds) {
            pr("updating account summary");
            AVAILABLE_CASH = Double.parseDouble(value);
            outputToGeneral("available cash is ", AVAILABLE_CASH);
        }
    }

    @Override
    public void accountSummaryEnd() {
        pr("summary end");
    }
}
