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
import static client.OrderStatus.*;
import static client.Types.Action.BUY;
import static client.Types.Action.SELL;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static Trader.TradingUtility.*;
import static java.lang.Double.MAX_VALUE;
import static java.lang.Double.MIN_VALUE;
import static java.lang.Math.floor;
import static java.lang.Math.round;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.stream.Collectors.*;
import static utility.Utility.*;

class ProfitTargetTrader implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler
        , ApiController.IAccountSummaryHandler {

    private static volatile double AVAILABLE_CASH = 0.0;
    private static final double DELTA_TOTAL_LIMIT = 450000;
    //    private static final double DELTA_LIMIT_EACH = DELTA_TOTAL_LIMIT / 3.0;
    private static final double CURRENT_REFILL_N = 2.0; //refill times now due to limited delta
    private static final double IDEAL_REFILL_N = 20.0; //ideally how many times to refill
    private static final double MAX_DRAWDOWN_TARGET = 0.8;
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData
            = new ConcurrentSkipListMap<>();
    private static volatile Map<String, Double> lastYearCloseMap = new ConcurrentHashMap<>();
    private static volatile Map<String, Double> yesterdayCloseMap = new ConcurrentHashMap<>();

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>>
            twoDayData = new ConcurrentSkipListMap<>(String::compareTo);


    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>>
            orderSubmitted = new ConcurrentHashMap<>();
    private static volatile Map<Integer, Double> pendingAddingDeltaMap = new ConcurrentHashMap<>();

    //    private static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>>
    //            orderStatus = new ConcurrentHashMap<>();
    private static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>>
            openOrders = new ConcurrentSkipListMap<>();
    //data
    private volatile static Map<String, Double> px = new ConcurrentHashMap<>();
    private static Map<String, LocalDateTime> lastPxTimestamp = new ConcurrentHashMap<>();
    private volatile static Map<String, Double> costMapAtStart = new ConcurrentSkipListMap<>();
    private volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();
    private volatile static Map<String, Decimal> symbPos = new ConcurrentSkipListMap<>(String::compareTo);
    private volatile static Map<String, Double> symbDelta = new ConcurrentSkipListMap<>(String::compareTo);
    private static Map<String, Double> twoDayPctMap = new ConcurrentHashMap<>();
    private static Map<String, Double> oneDayPctMap = new ConcurrentHashMap<>();
    private static Map<String, Integer> symbolContractIDMap = new ConcurrentHashMap<>();
    private static Map<String, List<ExecutionAugmented>> tradeKeyExecutionMap = new ConcurrentHashMap<>();
    private static Map<Integer, Double> orderIDPnlMap = new ConcurrentHashMap<>();
    //    private static Map<Integer, Boolean> orderFilledMap = new ConcurrentHashMap<>();
    private static Set<Integer> filledOrdersSet = new HashSet();
    private static Set<Integer> filledOrderStatusSet = new HashSet();
    //    private static Map<Integer, Double> orderIDPnlMap2 = new ConcurrentHashMap<>();
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
    private static volatile AtomicInteger tradeID = new AtomicInteger(MASTERID + 1);
//    private static Map<String, Double> baseDeltaMap = new HashMap<>();

    private static final int GATEWAY_PORT = 4001;
    private static final int TWS_PORT = 7496;
    //    private static final int PORT_TO_USE = TWS_PORT;
    private static final int PORT_TO_USE = GATEWAY_PORT;

    private static Map<String, Double> rng = new HashMap<>();

    private ProfitTargetTrader() throws IOException {
        outputToGeneral("*****START***** HKT:", hkTime(), "EST:", usDateTime(),
                "MASTERID:", MASTERID, "\n", "mkt start time today:", TODAY930);
        outputToOrders("", "*****START***** HKT:", hkTime(), "EST:", usDateTime());
        outputToPnl("*****START***** HKT:", hkTime(), "EST:", usDateTime());
        outputToFills("*****START***** HKT:", hkTime(), "EST:", usDateTime());
        pr("costTgt", Math.pow(MAX_DRAWDOWN_TARGET, 1 / (IDEAL_REFILL_N - 1)));
        pr("until mkt start time:", Duration.between(TODAY930, getESTDateTimeNow()).toMinutes(), "mins");

        Files.lines(Paths.get(RELATIVEPATH + "interestListUS")).map(l -> l.split(" "))
                .forEach(a -> {
                    //pr("whole line", a);
                    pr("a[0]", a[0]);
                    String stockName = a[0].equalsIgnoreCase("BRK") ? "BRK B" : a[0];
                    registerContract(generateUSStockContract(stockName));
                });

//        Files.lines(Paths.get(RELATIVEPATH + "baseDelta")).map(l -> l.split(" "))
//                .forEach(a -> {
//                    //pr("whole line", a);
//                    pr("a[0]", a[0]);
//                    String stockName = a[0].equalsIgnoreCase("BRK") ? "BRK B" : a[0];
//                    baseDeltaMap.put(stockName, Double.parseDouble(a[1]));
//                    pr(baseDeltaMap);
//                });
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

//    private static double deltaLimitEach(String s) {
////        return s.equalsIgnoreCase("SPY") ? ProfitTargetTrader.DELTA_TOTAL_LIMIT / 4 :
////                ProfitTargetTrader.DELTA_LIMIT_EACH;
//        return DELTA_TOTAL_LIMIT / 4.0;
//    }

    //45k
    private static Decimal getLot(double price) {
        return Decimal.get(Math.max(0, Math.floor(DELTA_TOTAL_LIMIT / 6.0 /
                price / CURRENT_REFILL_N)));
    }

    private static double costTgt(String symb) {
        return mins(0.98, 1 - rng.getOrDefault(symb, 0.0));
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
        return Math.max(minProfitMargin(s), 1 + rng.getOrDefault(s, 0.0) * 0.8);
    }

    private void connectAndReqPos() {
        api = new ApiController(new DefaultConnectionHandler(),
                new DefaultLogger(), new DefaultLogger());
        CountDownLatch l = new CountDownLatch(1);

        try {
            api.connect("127.0.0.1", PORT_TO_USE, 5, "");
            l.countDown();
            pr("Latch counted down", PORT_TO_USE, getESTDateTimeNow().format(MdHmmss));
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
        pr("computing historical data for", s);
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
                double yesterdayClose = ytdDayData.get(s).lowerEntry(getESTDateTimeNow().toLocalDate())
                        .getValue().getClose();
                pr("stock yesterday close ", s, yesterdayClose);
                yesterdayCloseMap.put(s, yesterdayClose);
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
        ytdReturn.put(symb, -1.0);
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
            outputToSymbol(s, getESTLocalTimeNow(), "no blocking orders,empty");
            return true;
        }

        if (orderSubmitted.get(s).size() > 15) {
            outputToSymbol(s, getESTLocalTimeNow(), "order size 15, meaning something is wrong", orderSubmitted.get(s));
            return false;
        }

        if (orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
                .filter(e -> !e.isFinished()).count() > 6) {
            outputToSymbol(s, getESTLocalTimeNow(), "maximum unfinished orders exceeding 6", orderSubmitted.get(s));
            return false;
        }
//        outputToSymbol(s, "no blocking buy orders orderSubmitted nonempty:"
//                , orderSubmitted.get(s));

        if (orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
                .allMatch(OrderStatus::isFinished)) {
            outputToSymbol(s, "all orders finished", orderSubmitted.get(s));
            return true;
        } else {
            outputToSymbol(s, "no blocking buy orders: All submitted orders", orderSubmitted.get(s));
            outputToSymbol(s, "no blocking buy orders: Active orders:",
                    orderSubmitted.get(s).entrySet().stream()
                            .filter(e -> !e.getValue().getOrderStatus().isFinished())
                            .collect(toList()));
            outputToSymbol(s, "active orders grouped by buysell:",
                    orderSubmitted.get(s).entrySet().stream()
                            .filter(e -> !e.getValue().getOrderStatus().isFinished())
                            .collect(groupingBy(e -> e.getValue().getOrder().action()
                                    , mapping(e -> e.getValue().getOrder().orderId(), toList()))));

            return orderSubmitted.get(s).entrySet().stream()
                    .filter(e -> !e.getValue().getOrderStatus().isFinished())
                    .noneMatch(e -> e.getValue().getOrder().action() == BUY);
        }
    }

    private static boolean noBlockingSellOrders(String s) {
        if (orderSubmitted.get(s).isEmpty()) {
            //outputToSymbol(s, "orderSubmitted empty");
            return true;
        }
//        pr(s, "no blocking sell orders check orderSubmitted:", orderSubmitted.get(s));

        if (orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
                .allMatch(OrderStatus::isFinished)) {
            outputToSymbol(s, "all order finished:", orderSubmitted.get(s));
            return true;
        } else {
//            if (getESTLocalTimeNow().getMinute() < 5) {
//                outputToSymbol(s, "no blocking sell orders:", orderSubmitted.get(s));
//                outputToSymbol(s, "no blocking sell orders: nonFinished orders:",
//                        orderSubmitted.get(s).entrySet().stream()
//                                .filter(e -> !e.getValue().getOrderStatus().isFinished())
//                                .collect(toList()));
//                outputToSymbol(s, "groupby buysell:",
//                        orderSubmitted.get(s).entrySet().stream()
//                                .collect(groupingBy(e -> e.getValue().getOrder().action()
//                                        , mapping(e -> e.getValue().getOrder().orderId()
//                                                , toList()))));
//            }
            return orderSubmitted.get(s).entrySet()
                    .stream().filter(e -> !e.getValue().getOrderStatus().isFinished())
                    .noneMatch(e -> orderSubmitted.get(s).get(e.getKey()).getOrder()
                            .action() == SELL);
        }
    }


//    private static boolean noBlockingOrders(String s) {
//        if (!orderSubmitted.get(s).isEmpty()) {
//            pr(s, "no blocking orders check:", orderSubmitted.get(s));
//        }
//        return orderSubmitted.get(s).isEmpty() ||
//                orderSubmitted.get(s).values().stream().map(OrderAugmented::getOrderStatus)
//                        .allMatch(OrderStatus::isFinished);
//    }

    private static double pxDivCost(double price, String symb) {
        if (costMap.getOrDefault(symb, 0.0) != 0.0) {
            return price / costMap.get(symb);
        }
        return 1;
    }

    private static boolean checkIfDeltaOK(String symb) {
//        double baseDelta = baseDeltaMap.getOrDefault(symb, 0.0);

//        if (orderSubmitted.entrySet().stream()
//                .filter(e -> e.getValue().entrySet().stream().filter(e1 -> e1.getValue().getOrderStatus() == Submitted)
//                        .map(e->e.get)) <
//                AVAILABLE_CASH) {
//            return false;
//        }

        return totalDelta < DELTA_TOTAL_LIMIT
                && (symbDelta.getOrDefault(symb, MAX_VALUE) < DELTA_TOTAL_LIMIT / 6.0);
    }

//    private static boolean checkDeltaImpact(String symb, double price) {
//        double addition = getLot(price).longValue() * price; //not accurate, because you could have space for 1/3 of order size
////        double baseDelta = baseDeltaMap.getOrDefault(symb, 0.0);
////        pr(symb, "check delta impact", "nowDelta+addition<TotalLimit:",
////                aggregateDelta + addition < DELTA_TOTAL_LIMIT,
////                "deltaStock+Inc<Limit:", symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) +
////                        getSizeFromPrice(price).longValue() * price < DELTA_EACH_LIMIT);
//
//        return addition < AVAILABLE_CASH && totalDelta + addition < DELTA_TOTAL_LIMIT &&
//                (symbDelta.getOrDefault(symb, MAX_VALUE) + addition < DELTA_TOTAL_LIMIT / 4.0);
//    }

//    private static double buyLowerFactor(String symb) {
//        return Math.min(0.998, 1 - rng.getOrDefault(symb, 0.0) / 4.0);
//    }

    //convex function to handle crashes
    private static double buyFactor(String symb, int i) {
//        if (i == 0) {
//            return 1;
//        }
        return mins(1 - 0.005 * Math.pow(4, i),
                1 - Math.pow(4, i) * rng.getOrDefault(symb, 0.0) / 2.5);
    }


    //0.005, 0.02, 0.045 handle jumps
    private static double sellFactor(String symb, int i) {
//        if (i == 0) {
//            return 1;
//        }

        return maxs(1 + 0.005 * Math.pow(3, i),
                1 + Math.pow(3, i) * rng.getOrDefault(symb, 0.0) / 3.0);
    }


//    private static double refillPx(String symb, double px, long pos, double costPerShare) {
//        if (px <= 0.0 || pos <= 0.0 || costPerShare <= 0.0) {
//            return 0.0;
//        }
////        double currentCostBasis = costPerShare * pos;
////        double lowerTgt = costTgt(symb);
////        double buySize = getLot(symb, px).longValue();
//        return costPerShare * buyFactor(symb, 4);
////        return Math.min(costPerShare,
////                (costPerShare * lowerTgt * (pos + buySize) - currentCostBasis) / buySize);
//    }

    private static double refillPx(String symb, double px, long pos, double costPerShare) {
        if (px <= 0.0 || pos <= 0.0 || costPerShare <= 0.0) {
            return 0.0;
        }

        double lowerTgt = mins(0.98, 1 - rng.getOrDefault(symb, 0.0));
        double buySize = getLot(px).longValue() / 2.0;

        return Math.min(costPerShare * lowerTgt,
                (costPerShare * lowerTgt * (pos + buySize) - costPerShare * pos) / buySize);
    }

    private static void tryToTrade(Contract ct, double px, LocalDateTime t) {
        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            return;
        }
        String s = ibContractToSymbol(ct);
        if (bidMap.getOrDefault(s, 0.0) == 0.0
                || askMap.getOrDefault(s, 0.0) == 0.0) {
            outputToError(s, t.toLocalTime(), "no bid or no ask when trying to trade");
            outputToError(s, t.toLocalTime(), "cost/bid/ask:",
                    costMap.getOrDefault(s, 0.0), bidMap.getOrDefault(s, 0.0)
                    , askMap.getOrDefault(s, 0.0));
            //can continue, trade at px
//            return;
        }
        double cost = costMap.getOrDefault(s, 0.0);

        if (!ct.currency().equalsIgnoreCase("USD")) {
            outputToGeneral(usDateTime(), "only USD stock allowed, s:", ct.symbol());
            return;
        }

        if (!twoDayPctMap.containsKey(s) || !oneDayPctMap.containsKey(s)) {
            pr(s, "no percentile info:", !twoDayPctMap.containsKey(s) ? "2day" : "",
                    !oneDayPctMap.containsKey(s) ? "1day" : "");
            return;
        }

        double twoDayP = twoDayPctMap.getOrDefault(s, 100.0);
        double oneDayP = oneDayPctMap.getOrDefault(s, 100.0);
        Decimal pos = symbPos.get(s);
        long posLong = pos.longValue();

        //if(order)

        if (oneDayP < 10 && twoDayP < 20 && checkIfDeltaOK(s)) {
            if (!noBlockingBuyOrders(s)) {
                outputToSymbol(s, t, "no blocking orders ");
                return;
            }

            if (!ytdReturn.containsKey(s) || ytdReturn.getOrDefault(s, MIN_VALUE) < -0.1) {
                if (t.getSecond() < 10 && t.getMinute() < 5) {
                    outputToSymbol(s, t, !ytdReturn.containsKey(s) ?
                            "ytdReturn not available, quitting inventory adder" :
                            str("Adder: ytd < -10% cannot trade:" +
                                    ytdReturn.getOrDefault(s, MIN_VALUE)));
                    outputToError(s, t, !ytdReturn.containsKey(s) ?
                            "ytdReturn not available, quitting inventory adder" :
                            str("Adder: ytd < -10% cannot trade:" +
                                    ytdReturn.getOrDefault(s, MIN_VALUE)));
                }
                return;
            }

            if (pos.isZero()) {
                outputToSymbol(s, "*1Buy*@" + px, t.format(MdHmmss), "1dp:" + oneDayP, "2dp:" + twoDayP);
                outputToSymbol(s, "cash remaining:", AVAILABLE_CASH);
                inventoryAdder(ct, px, t, getLot(px));
            } else if (pos.longValue() > 0 && cost != 0.0) {
                if (px < refillPx(s, px, posLong, cost)) {
                    outputToSymbol(s, "*REFILL*@" + px, t.format(MdHmmss),
                            "delta:" + round(symbDelta.getOrDefault(s, 0.0) / 1000.0) + "k",
                            "1dp:" + oneDayP, "2dp:" + twoDayP,
                            "cost:" + round1(costMap.get(s)),
                            "px/cost:" + round4(pxDivCost(px, s)),
                            "refillPx:" + round2(refillPx(s, px, posLong, cost)),
                            "avgRng:" + round4(rng.getOrDefault(s, 0.0)));
                    inventoryAdder(ct, px, t, getLot(px));
                }
            }
        }

        if (pos.longValue() > 0 && twoDayP > 50 && cost != 0.0) {
            double pOverCost = pxDivCost(px, s);
            if (pOverCost > tgtProfitMargin(s)) {
                if (!noBlockingSellOrders(s)) {
                    return;
                }

                outputToSymbol(s, "******************CUT**************************");
                outputToSymbol(s, "CUT", t.format(MdHmmss),
                        "1dP:" + oneDayP, "2dp:" + twoDayP, "px" + px,
                        "cost:" + costMap.get(s),
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
//                pr(t.format(Hmmss), "last p:", symb, price);
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
            if (costMapAtStart.getOrDefault(s, 0.0) == 0.0) {
                costMapAtStart.put(s, avgCost);
            }
            symbPos.put(s, position);
            costMap.put(s, avgCost);
            outputToSymbol(s, "updating pos:", s, usDateTime(),
                    "pos:" + position, "cost:" + round2(avgCost));
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

    private static void periodicCheckOrders() {
        pr("***********check orders***********");
        if (orderSubmitted.isEmpty()) {
            return;
        }

        orderSubmitted.entrySet().stream().forEach(e -> {
            String s = e.getKey();
//            double cost = costMap.getOrDefault(s, 0.0);
            double costInitial = costMapAtStart.getOrDefault(s, 0.0);
            e.getValue().entrySet().forEach(o -> {
                if (o.getValue().getOrderStatus() != Filled) {
                    pr(o.getKey(), s, o.getValue().getOrderStatus(),
                            o.getValue().getOrder().action(),
                            o.getValue().getOrder().totalQuantity().longValue(),
                            "@" + o.getValue().getOrder().lmtPrice());
                } else {
                    if (o.getValue().getOrder().action() == SELL) {
                        outputToGeneral(o.getKey(), s, "FILLED", "SELL",
                                o.getValue().getOrder().totalQuantity().longValue()
                                , "lmt@" + o.getValue().getOrder().lmtPrice()
                                , "Filled@" + o.getValue().getAvgFillPrice()
                                , "commission:" + o.getValue().getCommission()
                                , "IBPnl:" + round2(o.getValue().getIBPnl())
                                , "computePnl:" + round2(o.getValue().computedRealizedPnl(costInitial)));
                    } else {
                        outputToGeneral(o.getKey(), s, "FILLED", "BUY",
                                o.getValue().getOrder().totalQuantity().longValue(),
                                "@" + o.getValue().getOrder().lmtPrice(),
                                "Filled@", o.getValue().getAvgFillPrice(),
                                "commission:" + o.getValue().getCommission());
                    }
                }
            });
        });
//        pr("***********FINISH check orders***********");
    }

    private static void periodicPnl() {
        pr("***********Periodic PnL****************");
        targets.forEach(s -> {
            if (symbPos.containsKey(s)) {
                if (px.getOrDefault(s, 0.0) != 0.0 && costMap.getOrDefault(s, 0.0) != 0.0) {
                    long pos = symbPos.get(s).longValue();
                    double price = px.get(s);
                    double cost = costMap.get(s);
                    double yesterdayClose = yesterdayCloseMap.get(s);
                    double unrealizedPnl = pos * (price - cost);
                    double realizedPnl = computeRealizedPnl(s);
                    double mtmPnl = pos * (price - yesterdayClose);
                    pr(s, "pos:" + pos, "cost:" + round2(cost), "px:" + price
                            , "yestClose:" + yesterdayClose
                            , "unrealized:" + round2(unrealizedPnl),
                            "mtmPnl:" + round2(mtmPnl), "realized:" + round2(realizedPnl));
                }
            }
        });
//        pr("***********FINISH Periodic PnL****************");
    }

    private static double computeRealizedPnl(String s) {
        if (costMap.getOrDefault(s, 0.0) == 0.0) {
            return 0.0;
        }

        if (orderSubmitted.get(s).isEmpty()) {
            return 0.0;
        }

        double cost = costMap.get(s);
        return orderSubmitted.get(s).values().stream()
                .filter(orderAugmented -> orderAugmented.getOrderStatus() == Filled)
                .mapToDouble(o -> o.computedRealizedPnl(cost))
                .sum();
    }

    private static void periodicCompute() {
        pr("***********Periodic Compute****************");
        targets.forEach(s -> {
            if (symbPos.containsKey(s)) {
                if (px.getOrDefault(s, 0.0) != 0.0 && costMap.getOrDefault(s, 0.0) != 0.0) {
                    pr(s, "pos:" + symbPos.get(s),
                            "p:" + px.get(s),
                            "lastTime:" + (lastPxTimestamp.containsKey(s) ?
                                    lastPxTimestamp.get(s).format(Hmm) : "n/a"),
                            "1dp:" + (oneDayPctMap.containsKey(s) ? round(oneDayPctMap.get(s)) : "n/a"),
                            "2dp:" + (twoDayPctMap.containsKey(s) ? round(twoDayPctMap.get(s)) : "n/a"),
                            "delta:" + round(symbPos.get(s).longValue() * px.get(s) / 1000.0) + "k",
                            "cost:" + round1(costMap.get(s)),
                            "rtn:" + round(1000.0 * (px.get(s) / costMap.get(s) - 1)) / 10.0 + "%",
                            "#:" + getLot(px.get(s)),
                            "costTgt:" + round2(costTgt(s)),
                            "refil@" + round1(refillPx(s, px.get(s), symbPos.get(s).longValue(), costMap.get(s))),
                            "refil/Cost:" + round2(refillPx(s, px.get(s), symbPos.get(s).longValue(), costMap.get(s)) /
                                    costMap.get(s)),
                            "refil/Px:" + round2(refillPx(s, px.get(s)
                                    , symbPos.get(s).longValue(), costMap.get(s)) / px.get(s)),
                            "rng:" + round(1000.0 * rng.getOrDefault(s, 0.0)) / 10.0 + "%",
                            "bFactor:" + round4(buyFactor(s, 1)) + " "
                                    + round4(buyFactor(s, 2)) + " " + round4(buyFactor(s, 3)),
                            "sFactor:" + round4(sellFactor(s, 1))
                                    + " " + round4(sellFactor(s, 2)) + " "
                                    + round4(sellFactor(s, 3)),
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
//                pr(usTime(), s, "1DP:" + oneDayP, "2DP:" + twoDayP);
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
        double basePrice = Math.min(px, bidMap.getOrDefault(s, px));

        for (int i = 0; i < 2; i++) {
            int id = tradeID.incrementAndGet();
            double bidPrice = r(basePrice * buyFactor(s, i));
            Decimal size = Decimal.get(floor(lotSize.longValue() / 3.0));
            Order o = placeBidLimitTIF(id, bidPrice, size, DAY);
            orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, ADDER, Created));
            double delta = o.totalQuantity().longValue() * bidPrice;
            if (pendingAddingDeltaMap.values().stream().mapToDouble(v -> v).sum() + delta > AVAILABLE_CASH) {
                outputToSymbol(s, "not enough cash to trade. BALANCE:" + AVAILABLE_CASH, "pending already:" +
                                pendingAddingDeltaMap.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey()).toList(),
                        "delta trying to add:" + round2(delta));
                return;
            }
            placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));
            pendingAddingDeltaMap.put(o.orderId(), delta);
            outputToOrders(s, i + ":" + o.orderId(), s, "BUY", o.totalQuantity().longValue(),
                    "@" + bidPrice, t.toLocalTime().format(Hmm), "bid is:" + bidMap.getOrDefault(s, 0.0));

            outputToSymbol(s, t.toLocalTime().format(Hmm),
                    i + ":", s, "orderID:" + o.orderId(), "tradeID:" + id, "BUY",
                    size, "@" + bidPrice, "factor:" + buyFactor(s, i)
                    , orderSubmitted.get(s).get(o.orderId()));
        }
        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
    }

//    private static void inventoryAdder(Contract ct, double px, LocalDateTime t, Decimal lotSize) {
//        String s = ibContractToSymbol(ct);
//        double basePrice = Math.min(px, bidMap.getOrDefault(s, px));
//        String timeStr = t.toLocalTime().format(Hmm);
//
//        int id0 = tradeID.incrementAndGet();
//        double bidPx0 = r(basePrice);
//        Decimal size0 = Decimal.get(round(lotSize.longValue() / 3.0));
//        Order o0 = placeBidLimitTIF(id0, bidPx0, size0, DAY);
//        orderSubmitted.get(s).put(o0.orderId(),
//                new OrderAugmented(ct, t, o0, ADDER, Created));
//        placeOrModifyOrderCheck(api, ct, o0, new OrderHandler(s, o0.orderId()));
//        outputToOrders(s, o0.orderId(), s, o0.action(), o0.totalQuantity().longValue(),
//                "@" + bidPx0, timeStr);
//        outputToSymbol(s, "order ID0:" + o0.orderId(), s, "tradeID0:" + id0, o0.action(),
//                "px0:" + bidPx0, "lot0:" + size0, orderSubmitted.get(s).get(o0.orderId()));
//
//        int id1 = tradeID.incrementAndGet();
//        double bidPx1 = r(basePrice * buyFactor(s, 1));
//        Decimal size1 = Decimal.get(round(lotSize.longValue() / 3.0));
//        Order o1 = placeBidLimitTIF(id1, bidPx1, size1, DAY);
//        orderSubmitted.get(s).put(o1.orderId(),
//                new OrderAugmented(ct, t, o1, ADDER, Created));
//        placeOrModifyOrderCheck(api, ct, o1, new OrderHandler(s, o1.orderId()));
//        outputToOrders(s, o1.orderId(), s, o1.action(), o1.totalQuantity().longValue(),
//                "@" + bidPx1, timeStr);
//        outputToSymbol(s, "order ID1:" + o1.orderId(), s, "tradeID1:" + id1, o1.action(),
//                "px1:" + bidPx1, "lot1:" + size1, orderSubmitted.get(s).get(o1.orderId()));
//
//        int id2 = tradeID.incrementAndGet();
//        double bidPx2 = r(basePrice * buyFactor(s, 2));
//        Decimal size2 = Decimal.get(round(lotSize.longValue() / 3.0));
//        Order o2 = placeBidLimitTIF(id2, bidPx2, size2, DAY);
//        orderSubmitted.get(s).put(o2.orderId(), new OrderAugmented(ct, t, o2, ADDER, Created));
//        placeOrModifyOrderCheck(api, ct, o2, new OrderHandler(s, o2.orderId()));
//        outputToOrders(s, o2.orderId(), s, "BUY", o2.totalQuantity().longValue(),
//                "@" + bidPx2, timeStr);
//        outputToSymbol(s, "orderID2:" + o2.orderId(), "tradeID2:" + id2, o2.action(),
//                "px2:" + bidPx2, "lot2:" + size2, "buyfactor2:" + round4(buyFactor(s, 2)),
//                orderSubmitted.get(s).get(o2.orderId()));
//
//        //third order, lower buy price further
//        int id3 = tradeID.incrementAndGet();
//        double bidPx3 = r(basePrice * buyFactor(s, 3));
//        Decimal size3 = Decimal.get(round(lotSize.longValue() / 3.0));
//        Order o3 = placeBidLimitTIF(id3, bidPx3, size3, DAY);
//        orderSubmitted.get(s).put(o3.orderId(), new OrderAugmented(ct, t, o3, ADDER, Created));
//        placeOrModifyOrderCheck(api, ct, o3, new OrderHandler(s, o3.orderId()));
//        outputToOrders(s, o3.orderId(), s, "BUY", o3.totalQuantity().longValue(),
//                "@" + bidPx3, timeStr);
//        outputToSymbol(s, "orderID3:" + o3.orderId(), "tradeID3:" + id3, o3.action(),
//                "px3:" + bidPx3, "lot3:" + size3, "buyfactor3:" + round4(buyFactor(s, 3)),
//                orderSubmitted.get(s).get(o3.orderId()));
//
//        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
//        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
//    }

    private static void inventoryCutter(Contract ct, double px, LocalDateTime t) {
        String s = ibContractToSymbol(ct);
        double currDelta = symbDelta.getOrDefault(s, 0.0);
        Decimal pos = symbPos.get(s);
        double cost = costMap.get(s);

        if (currDelta == 0.0) {
            outputToSymbol(s, "error in inventory cutter", "currentdelta:" + currDelta);
            outputToError(s, "error in inventory cutter", "currentdelta:" + currDelta);
            return;
        }

        double basePrice = maxs(askMap.getOrDefault(s, px), costMap.get(s) * tgtProfitMargin(s));

        double partitions = 3.0;
        long remainingPos = pos.longValue();
        double remainingDelta = pos.longValue() * px;


        for (int i = 0; i < partitions; i++) {         //0,1,2
            if (remainingPos <= 0.0) {
                outputToSymbol(s, getESTDateTimeNow(), "remaining position <= 0, break");
                break;
            }

            int id = tradeID.incrementAndGet();
            double offerPrice = r(basePrice * sellFactor(s, i));
            Decimal sellQ;

            if (remainingDelta < 1000.0 || i == partitions - 1) {
                sellQ = Decimal.get(remainingPos);
            } else {
                sellQ = Decimal.get(floor(pos.longValue() / partitions));
            }

            Order o = placeOfferLimitTIF(id, offerPrice, sellQ, DAY);
            orderSubmitted.get(s).put(o.orderId(), new OrderAugmented(ct, t, o, CUTTER, Created));
            placeOrModifyOrderCheck(api, ct, o, new OrderHandler(s, o.orderId()));

            outputToOrders(s, i + ":" + o.orderId(), s, "SELL", o.totalQuantity().longValue(),
                    "@" + offerPrice, t.toLocalTime().format(Hmm));
            outputToSymbol(s, i + ":", "SELL", orderSubmitted.get(s).get(o.orderId()),
                    "target margin:" + round5(tgtProfitMargin(s)),
                    "cost*target margin:" + round2(cost * tgtProfitMargin(s)),
                    "askPx:" + askMap.getOrDefault(s, 0.0));
            remainingPos = remainingPos - sellQ.longValue();
            remainingDelta = remainingPos * px;
        }
        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
    }


//    private static void inventoryCutter(Contract ct, double px, LocalDateTime t) {
//        String s = ibContractToSymbol(ct);
//        double currentDelta = symbDelta.getOrDefault(s, 0.0);
//        Decimal pos = symbPos.get(s);
//        double cost = costMap.get(s);
//        String timeStr = t.toLocalTime().format(Hmm);
//
//        if (currentDelta == 0.0) {
//            outputToSymbol(s, "error in inventory cutter", "currentdelta:" + currentDelta);
//            outputToError(s, "error in inventory cutter", "currentdelta:" + currentDelta);
//            return;
//        }
//
//        double basePrice = maxs(askMap.get(s), costMap.get(s) * tgtProfitMargin(s));
//
//        int id0 = tradeID.incrementAndGet();
//        double offerPrice0 = r(basePrice);
//        Decimal sellQ0 = currentDelta > 2000.0 ? Decimal.get(round(pos.longValue() / 4.0)) : pos;
//        Order o0 = placeOfferLimitTIF(id0, offerPrice0, sellQ0, DAY);
//        orderSubmitted.get(s).put(o0.orderId(), new OrderAugmented(ct, t, o0,
//                CUTTER, Created));
//        placeOrModifyOrderCheck(api, ct, o0, new OrderHandler(s, o0.orderId()));
//        outputToOrders(s, o0.orderId(), s, "SELL", o0.totalQuantity().longValue(),
//                "@" + offerPrice0, timeStr);
//        outputToSymbol(s, "sell0:", orderSubmitted.get(s).get(o0.orderId()),
//                "reqMargin:" + round5(tgtProfitMargin(s)),
//                "targetPx:" + round2(cost * tgtProfitMargin(s)),
//                "askPx:" + askMap.getOrDefault(s, 0.0));
//
//        if (currentDelta > 2000.0) {
//            int id1 = tradeID.incrementAndGet();
//            double offerPrice1 = r(basePrice * sellFactor(s, 1));
//            Decimal sellQ1 = currentDelta > 1000.0 ?
//                    Decimal.get(floor(pos.longValue() / 4.0)) : pos;
//            Order o1 = placeOfferLimitTIF(id1, offerPrice1, sellQ1, DAY);
//            orderSubmitted.get(s).put(o1.orderId(), new OrderAugmented(ct, t, o1, CUTTER, Created));
//            placeOrModifyOrderCheck(api, ct, o1, new OrderHandler(s, o1.orderId()));
//            outputToOrders(s, o1.orderId(), s, "SELL", o1.totalQuantity().longValue(),
//                    "@" + offerPrice1, timeStr);
//            outputToSymbol(s, "sell1:", orderSubmitted.get(s).get(o1.orderId()),
//                    "reqMargin:" + round5(tgtProfitMargin(s)),
//                    "targetPx:" + round2(cost * tgtProfitMargin(s)),
//                    "askPx:" + askMap.getOrDefault(s, 0.0));
//
//            Decimal sellQ2 = Decimal.get(floor(pos.longValue() / 4.0));
//            int id2 = tradeID.incrementAndGet();
//            Order o2 = placeOfferLimitTIF(id2, r(basePrice * sellFactor(s, 2)), sellQ2, DAY);
//            orderSubmitted.get(s).put(o2.orderId(), new OrderAugmented(ct, t, o2, CUTTER, Created));
//            placeOrModifyOrderCheck(api, ct, o2, new OrderHandler(s, o2.orderId()));
//            outputToOrders(s, o2.orderId(), s, "SELL", o2.totalQuantity().longValue(),
//                    "@" + r(basePrice * sellFactor(s, 2)), timeStr);
//            outputToSymbol(s, "sell2:", orderSubmitted.get(s).get(o2.orderId()));
//
//            Decimal sellQ3 = Decimal.get(pos.longValue() - sellQ0.longValue()
//                    - sellQ1.longValue() - sellQ2.longValue());
//
//            if (sellQ3.longValue() > 0) {
//                int id3 = tradeID.incrementAndGet();
//                Order o3 = placeOfferLimitTIF(id3, r(basePrice * sellFactor(s, 3)), sellQ3, DAY);
//                orderSubmitted.get(s).put(o3.orderId(), new OrderAugmented(ct, t, o3, CUTTER, Created));
//                placeOrModifyOrderCheck(api, ct, o3, new OrderHandler(s, o3.orderId()));
//                outputToOrders(s, o3.orderId(), s, "SELL", o3.totalQuantity().longValue(),
//                        "@" + r(basePrice * sellFactor(s, 3)), t.toLocalTime().format(Hmm));
//                outputToSymbol(s, "sell3:", orderSubmitted.get(s).get(o3.orderId()));
//            }
//        }
//        outputToSymbol(s, "2D$:" + genStats(twoDayData.get(s)));
//        outputToSymbol(s, "1D$:" + genStats(twoDayData.get(s).tailMap(TODAY230)));
//    }

    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String s = ibContractToSymbol(contract);

        if (!targets.contains(s)) {
            outputToSymbol(s, "not in profit target trader");
            return;
        }

        outputToSymbol(s, usDateTime(), "*openOrder* status:" + orderState.status(), order);

        if (orderSubmitted.get(s).containsKey(order.orderId())) {
            orderSubmitted.get(s).get(order.orderId()).updateOrderStatus(orderState.status());
        } else {
            outputToSymbol(s, "openOrder does not contain order", s, order);
            outputToError(s, "openorders does not contain order", s, order);
            orderSubmitted.get(s).put(order.orderId(),
                    new OrderAugmented(contract, order, orderState.status()));
        }

        if (orderState.status() == Filled) {
            pendingAddingDeltaMap.put(order.orderId(), 0.0);
            if (!filledOrdersSet.contains(order.orderId())) {
                outputToFills(s, usDateTime(), "*openOrder* FILLED", order);
                filledOrdersSet.add(order.orderId());
            }
        }

        if (orderState.status().isFinished()) {
            outputToSymbol(s, usDateTime(), "*openOrder*:removing order.Status:" +
                    orderState.status(), order);
            if (openOrders.get(s).containsKey(order.orderId())) {
                openOrders.get(s).remove(order.orderId());
            }
            outputToSymbol(s, usDateTime(), "*openOrder*:after removal.OpenOrders:",
                    openOrders.get(s).keySet().stream().sorted(Comparator.naturalOrder()).toList());
        } else { //order is not finished
            openOrders.get(s).put(order.orderId(), order);
        }
        if (!openOrders.get(s).isEmpty()) {
            outputToSymbol(s, usDateTime(), "*openOrder* all live orders",
                    openOrders.get(s)
                            .entrySet().stream()
                            .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());
        }
    }

    @Override
    public void openOrderEnd() {
        outputToGeneral(usDateTime(), "*openOrderEnd*:print all " +
                        "openOrders Profit Target", openOrders,
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
            outputToError(usDateTime(),
                    "*orderStatus* orderID not found in ProfitTarget:", orderId);
            return;
        }

        outputToSymbol(s, usDateTime(), "*OrderStatus*:" + status,
                "orderId:" + orderId, "filled:" + filled, "remaining:" + remaining,
                "avgFillPx:" + avgFillPrice, "lastFillPx:" + lastFillPrice);

        if (status == Filled) {
            pendingAddingDeltaMap.put(orderId, 0.0);
            orderSubmitted.get(s).get(orderId).updateFilledPrice(avgFillPrice);
            orderSubmitted.get(s).get(orderId).updateFilledQuantity(filled);
            if (!filledOrderStatusSet.contains(orderId)) {
                filledOrderStatusSet.add(orderId);
                outputToFills(s, usDateTime(), "*OrderStatus*: filled:" + orderId);
//            } else {
//                outputToFills(s, usDateTime(), orderId, "printed already");
            }
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
        if (errorCode != 2157) {
            outputToError("*openOrder* Error", usDateTime(), "orderId:" +
                    orderId, "errorCode:" + errorCode, "msg:" + errorMsg);
        }
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
            outputToError("commission report issue", "tradekey:" + tradeKey
                    , "report:" + commissionReport);
            return;
        }

        String s = tradeKeyExecutionMap.get(tradeKey).getFirst().getSymbol();

        if (orderSubmitted.containsKey(s) && !orderSubmitted.get(s).isEmpty()) {
            orderSubmitted.get(s).entrySet().stream()
                    .filter(e -> e.getValue().getOrder().orderId()
                            == tradeKeyExecutionMap.get(tradeKey).getFirst().getExec().orderId())
                    .forEach(e -> {
                        if (!orderIDPnlMap.containsKey(e.getKey()) && e.getValue().getOrder().action() == SELL) {
                            orderIDPnlMap.put(e.getKey(), commissionReport.realizedPNL());
                            e.getValue().updateIBPnl(commissionReport.realizedPNL());
                            outputToPnl(getESTLocalTimeNow().format(Hmm), s, e.getKey(),
                                    "SELL", e.getValue().getOrder().totalQuantity().longValue(),
                                    "@" + e.getValue().getOrder().lmtPrice()
                                    , "pnl:" + round2(commissionReport.realizedPNL()));
                        }

                        e.getValue().updateCommission(commissionReport.commission());

                        double computedPnl = e.getValue()
                                .computedRealizedPnl(costMapAtStart.getOrDefault(e.getValue().getSymbol(), 0.0));
                        if (e.getValue().getOrder().action() == SELL) {
                            outputToSymbol(s, "computed Pnl:" + computedPnl);
                        }
                        String output = str("1.*commission report*:" + e.getKey(),
                                "commission:" + round2(commissionReport.commission()),
                                e.getValue().getOrder().action() == SELL ?
                                        str("realized pnl:" +
                                                        round2(commissionReport.realizedPNL()),
                                                "computed Pnl:" + round2(computedPnl)) : "");
                        outputToSymbol(s, output);
                        outputToFills(s, output);
                        outputToFills(s, "*********************");
                    });

            orderSubmitted.get(s).forEach((_, value1) -> {
                if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).getFirst().getExec().orderId()) {
                    if (value1.getOrder().action() == SELL &&
                            !orderIDPnlMap.containsKey(value1.getOrder().orderId())) {
                        value1.updateIBPnl(commissionReport.realizedPNL());
                        orderIDPnlMap.put(value1.getOrder().orderId(), commissionReport.realizedPNL());
                        outputToPnl("2:", value1.getOrder().orderId(), value1.getOrder()
                                , "pnl:", commissionReport.realizedPNL());
                    }
                    value1.updateCommission(commissionReport.commission());
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
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCompute, 20L, 20L, TimeUnit.SECONDS);
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCheckOrders, 20L, 60L, TimeUnit.SECONDS);
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicPnl, 20L, 120L, TimeUnit.SECONDS);
        es.scheduleAtFixedRate(() -> {
            targets.forEach(s -> {
//                outputToSymbol(s, );
                outputToSymbol(s, "*Periodic Run*", usDateTime(), lastPxTimestamp.containsKey(s) ?
                        str("last live feed time:", lastPxTimestamp.get(s).format(MdHmm),
                                "been:" + Duration.between(lastPxTimestamp.get(s), getESTDateTimeNow()).toMinutes()
                                        + "min", "p:" + px.getOrDefault(s, 0.0),
                                costMap.getOrDefault(s, 0.0) == 0.0 ? "" : str(
                                        "cost:" + round1(costMap.get(s)),
                                        "p/cost:" + round3(px.getOrDefault(s, 0.0)
                                                / costMap.getOrDefault(s, 0.0)))) :
                        str(getESTLocalTimeNow().format(Hmmss)
                                , "no live feed"));
                if (symbDelta.getOrDefault(s, 0.0) > 0.0 && costMap.getOrDefault(s, 0.0) != 0.0) {
                    outputToSymbol(s, "p:" + px.getOrDefault(s, 0.0),
                            "rng:" + round(1000.0 * rng.getOrDefault(s, 0.0)) / 10.0 + "%",
                            "pos:" + symbPos.getOrDefault(s, Decimal.ZERO).longValue(),
                            "delt:" + round(symbDelta.getOrDefault(s, 0.0) / 1000.0) + "k",
                            "cost:" + round1(costMap.get(s)),
                            "fullLot:" + getLot(px.get(s)),
                            "refillP:" + round2(refillPx(s, px.get(s), symbPos.get(s).longValue(), costMap.get(s))),
                            "costTgt:" + round3(costTgt(s)),
                            "refillP/cost:" + round3(refillPx(s, px.get(s),
                                    symbPos.get(s).longValue(), costMap.get(s)) / costMap.get(s)),
                            "refillP/px:" + round3(refillPx(s, px.get(s), symbPos.get(s).longValue()
                                    , costMap.get(s)) / px.get(s)));
                }
                if (!orderSubmitted.get(s).isEmpty()) {
                    outputToSymbol(s, usDateTime(), "*chek orderStatus",
                            orderSubmitted.get(s).entrySet().stream()
                                    .collect(Collectors.toMap(Map.Entry::getKey,
                                            e -> e.getValue().getOrderStatus()))
                                    .entrySet().stream().sorted(Map.Entry.comparingByKey()).toList());
                }
                if (!openOrders.get(s).isEmpty()) {
                    outputToSymbol(s, usDateTime(), "*chek openOrders*:",
                            openOrders.get(s).keySet().stream()
                                    .sorted(Comparator.naturalOrder()).toList());
                }
                outputToSymbol(s, usDateTime(), px.getOrDefault(s, 0.0),
                        "2dP:" + twoDayPctMap.getOrDefault(s, 101.0),
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
            outputToGeneral(getESTLocalTimeNow().format(Hmmss),
                    "available cash is ", AVAILABLE_CASH);
        }
    }

    @Override
    public void accountSummaryEnd() {
        pr("summary end");
    }
}
