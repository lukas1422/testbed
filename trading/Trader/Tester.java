package Trader;

import api.OrderAugmented;
import api.TradingConstants;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import enums.StockStatus;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import utility.Utility;

import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.f1;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static java.lang.Math.round;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class Tester implements LiveHandler, ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {

    private static ApiController apiController;
    private static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    static volatile AtomicInteger tradeID = new AtomicInteger(100);
    static volatile AtomicInteger allOtherReqID = new AtomicInteger(10000);

    static volatile double aggregateDelta = 0.0;


    Contract gjs = generateHKStockContract("388");
    Contract xiaomi = generateHKStockContract("1810");
    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");

    Contract brk = generateUSStockContract("BRK B");

    Contract ul = generateUSStockContract("UL");

    private static Map<String, Integer> symbolConIDMap = new ConcurrentHashMap<>();

    private static final double PROFIT_LEVEL = 1.005;
    private static final double DELTA_LIMIT = 10000;
    private static final double DELTA_LIMIT_EACH_STOCK = 2000;

    static volatile NavigableMap<Integer, OrderAugmented> orderMap = new ConcurrentSkipListMap<>();

    static File outputFile = new File("trading/TradingFiles/output");
    //File f = new File("trading/TradingFiles/output");

    static volatile Map<String, StockStatus> stockStatusMap = new ConcurrentHashMap<>();

    //data
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData = new ConcurrentSkipListMap<>();
    private static Map<String, Double> latestPriceMap = new ConcurrentHashMap<>();
    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private static Map<String, Double> threeDayPctMap = new ConcurrentHashMap<>();
    private static Map<String, Double> oneDayPctMap = new ConcurrentHashMap<>();

    private static Map<String, Execution> tradeKeyExecutionMap = new ConcurrentHashMap<>();


    //historical data
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>> ytdDayData
            = new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> threeDayData
            = new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> todayData
            = new ConcurrentSkipListMap<>(String::compareTo);


    private static volatile Map<String, Double> lastYearCloseMap = new ConcurrentHashMap<>();


    //position
//    private volatile static Map<Contract, Decimal> contractPosMap =
//            new ConcurrentSkipListMap<>(Comparator.comparing(Utility::ibContractToSymbol));

    private volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();


    private volatile static Map<String, Decimal> symbolPosMap = new ConcurrentSkipListMap<>(String::compareTo);

    private volatile static Map<String, Double> symbolDeltaMap = new ConcurrentSkipListMap<>(String::compareTo);

    private static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);

    //avoid too many requests at once, only 50 requests allowed at one time.
    private static Semaphore histSemaphore = new Semaphore(45);

    //Trade
//    private static volatile Map<String, AtomicBoolean> addedMap = new ConcurrentHashMap<>();
//    private static volatile Map<String, AtomicBoolean> liquidatedMap = new ConcurrentHashMap<>();
    private static volatile Map<String, AtomicBoolean> tradedMap = new ConcurrentHashMap<>();

//    public static final LocalDateTime TODAY_MARKET_START_TIME =
//            LocalDateTime.of(LocalDateTime.now().toLocalDate()., LocalTime.of(9, 30));

    public static final LocalDateTime TODAY_MARKET_START_TIME =
            LocalDateTime.of(ZonedDateTime.now().withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate(),
                    ltof(9, 30));

    private Tester() {
        pr("initializing...");

//        outputToFile(str("Start time is", LocalDateTime.now()), outputFile);
//        File f = new File("trading/TradingFiles/output");
//        pr(f.getAbsolutePath());
        outputToFile(str("start time EST ", ZonedDateTime.now().withZoneSameInstant(ZoneId.of("America/New_York")))
                , outputFile);
        registerContract(wmt);
        registerContract(pg);
        registerContract(brk);
        registerContract(ul);
    }


    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new Utility.DefaultLogger(), new Utility.DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);
        boolean connectionStatus = false;


        try {
            pr(" using port 4001");
            ap.connect("127.0.0.1", 4001, 5, "");
            connectionStatus = true;
            l.countDown();
//            pr(" Latch counted down 4001 " + LocalTime.now());
            pr(" Latch counted down 4001 " + LocalDateTime.now(Clock.system(ZoneId.of("America/New_York")))
                    .format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        if (!connectionStatus) {
            pr(" using port 7496");
            ap.connect("127.0.0.1", 7496, 5, "");
            l.countDown();
            pr(" Latch counted down 7496" + LocalDateTime.now(Clock.system(ZoneId.of("America/New_York")))
                    .format(f1));
        }

        try {
            l.await();
            pr("connected");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        pr(" Time after latch released " + LocalTime.now());
//        Executors.newScheduledThreadPool(10).schedule(() -> reqHoldings(ap), 500, TimeUnit.MILLISECONDS);
        targetStockList.forEach(symb -> {
            pr("request hist day data: target stock symb ", symb);
            Contract c = generateUSStockContract(symb);
            if (!threeDayData.containsKey(symb)) {
                threeDayData.put(symb, new ConcurrentSkipListMap<>());
            }

//        pr("today so far ", threeDayData.getOrDefault(symbol, ""));

            if (!liveData.containsKey(symb)) {
                liveData.put(symb, new ConcurrentSkipListMap<>());
            }

            pr("requesting day data", symb);
            CompletableFuture.runAsync(() -> {
                //                histSemaphore.acquire();

                reqHistDayData(apiController, ibStockReqId.addAndGet(5), histCompatibleCt(c),
                        Tester::todaySoFar, 3, Types.BarSize._1_min);
            });
            CompletableFuture.runAsync(() -> {
                //                histSemaphore.acquire();
                reqHistDayData(apiController, ibStockReqId.addAndGet(5), histCompatibleCt(c), Tester::ytdOpen,
                        Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day);
            });
        });

//        reqHoldings(apDev);
        Executors.newScheduledThreadPool(10).schedule(() -> {
                    apiController.reqPositions(this);
                    apiController.reqLiveOrders(this);
                }, 500,
                TimeUnit.MILLISECONDS);
//        req1ContractLive(apDev, liveCompatibleCt(wmt), this, false);
        pr("req executions ");
        apiController.reqExecutions(new ExecutionFilter(), this);
//        apiController.reqPnLSingle();
        pr("requesting contract details");
//        registerContract(wmt);
//        registerContract(pg);
        Executors.newScheduledThreadPool(10).schedule(() -> {
            registerContract(wmt);
            registerContract(pg);
//            apiController.reqContractDetails(wmt,
//                    list -> list.forEach(a -> pr(a.contract().symbol(), a.contract().conid())));
        }, 1, TimeUnit.SECONDS);
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        targetStockList.add(symb);
        //symbolPosMap.put(symb, Decimal.ZERO);
        stockStatusMap.put(symb, StockStatus.UNKNOWN);
//        apiController.reqContractDetails(ct, list -> list.forEach(a -> {
//            pr(a.contract().symbol(), a.contract().conid());
//            symbolConIDMap.put(symb, a.contract().conid());
//        }));

        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    private static void todaySoFar(Contract c, String date, double open, double high, double low, double close,
                                   long volume) {
        String symbol = Utility.ibContractToSymbol(c);

        LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
                TimeZone.getTimeZone("America/New_York").toZoneId());

//        pr("today so far", symbol, ld, open);

        if (!date.startsWith("finished")) {
//            LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
//                    TimeZone.getDefault().toZoneId());
//            ZoneId zoneId = TimeZone.getDefault().toZoneId();
//            pr("default time zone is  ", zoneId);
//            TimeZone timeZoneLA = TimeZone.getTimeZone("America/New_York");
//            ZoneId zoneIdLA = timeZoneLA.toZoneId();
//            LocalDateTime.of(ZonedDateTime.now().withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate(),
//                    LocalTime.of(9, 30));

            threeDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
            liveData.get(symbol).put(ld, close);
        }
    }

    private static void ytdOpen(Contract c, String date, double open, double high, double low, double close,
                                long volume) {

        String symbol = Utility.ibContractToSymbol(c);
        if (!ytdDayData.containsKey(symbol)) {
            ytdDayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        if (!date.startsWith("finished")) {
            LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
            ytdDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
        } else {
            if (!ytdDayData.get(symbol).firstKey().isBefore(Trader.Allstatic.LAST_YEAR_DAY)) {
                pr("check YtdOpen", symbol, ytdDayData.get(symbol).firstKey());
            }
//            histSemaphore.release(1);
        }
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

//        LocalDate prevMonthCutoff = getPrevMonthCutoff(ct, getMonthBeginMinus1Day(t.toLocalDate()));
//        LocalDateTime dayStartTime = LocalDateTime.of(t.toLocalDate(), ltof(9, 30, 0));
//        LocalDate previousQuarterCutoff = getQuarterBeginMinus1Day(t.toLocalDate());
//        LocalDate previousHalfYearCutoff = getHalfYearBeginMinus1Day(t.toLocalDate());


        switch (tt) {
            case LAST:
                pr("last price", tt, symb, price, t.format(f1));
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);
                pr("inventory status", symb, stockStatusMap.get(symb));

                if (!symbolPosMap.get(symb).isZero()) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }

                //trade logic
//                if (lastYearCloseMap.getOrDefault(symbol, 0.0) > price && percentileMap.containsKey(symbol)) {

                // should change to US time, not china. Also 22 30 is without daylight savings.
//                t.toLocalTime().isAfter(LocalTime.of(22, 30))
                if (TRADING_TIME_PRED.test(getUSTimeNow())) {
                    if (threeDayPctMap.containsKey(symb) && oneDayPctMap.containsKey(symb)) {


                        if (stockStatusMap.get(symb) != StockStatus.BUYING_INVENTORY) {
                            if (aggregateDelta < DELTA_LIMIT
                                    && symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) < DELTA_LIMIT_EACH_STOCK) {
                                pr("first check", symb, threeDayPctMap.get(symb), oneDayPctMap.get(symb), symbolPosMap.get(symb));
                                if (threeDayPctMap.get(symb) < 40 && oneDayPctMap.get(symb) < 20 && symbolPosMap.get(symb).isZero()) {
                                    pr("second check", symb);
                                    inventoryAdder(ct, price, t, threeDayPctMap.get(symb), oneDayPctMap.get(symb));
                                }
                            }
                        }

                        if (symbolPosMap.get(symb).longValue() > 0) {
                            if (costMap.containsKey(symb)) {
                                pr(symb, "price/cost", price / costMap.getOrDefault(symb, Double.MAX_VALUE));
                                if (price / costMap.getOrDefault(symb, Double.MAX_VALUE) > PROFIT_LEVEL) {
//                                inventoryCutter(ct, price, t, oneDayPctMap.getOrDefault(symb, 0.0));
                                    inventoryCutter(ct, price, t);
                                }
                            }
                        }
                    }
                }

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
        if (!contract.symbol().equals("USD")) {
//            contractPosMap.put(contract, position);
            symbolPosMap.put(symb, position);
            costMap.put(symb, avgCost);
            if (position.longValue() > 0) {
                stockStatusMap.put(symb, StockStatus.HAS_INVENTORY);
            } else if (position.isZero()) {
                stockStatusMap.put(symb, StockStatus.NO_INVENTORY);
            }
        }

        pr("account, contract, position, avgcost", account, symb, position, avgCost);
//        contractPosMap.entrySet().stream().forEach(e -> pr(e.getKey().symbol(), e.getValue()));
//     pr("contract map", contractPosMap);
    }

    @Override
    public void positionEnd() {
        pr("position end");
        targetStockList.forEach(symb -> {
            pr(" symbol in positionEnd", symb);
            if (symbolPosMap.getOrDefault(symb, Decimal.ZERO).isZero()) {
                stockStatusMap.put(symb, StockStatus.NO_INVENTORY);
            }

            apiController.reqContractDetails(generateUSStockContract(symb), list -> list.forEach(a -> {
                pr(a.contract().symbol(), a.contract().conid());
                symbolConIDMap.put(symb, a.contract().conid());
            }));

            es.schedule(() -> {
                pr("Position end: requesting live for fut:", symb);
                req1ContractLive(apiController, liveCompatibleCt(generateUSStockContract(symb)), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    static void computePercentileAndDelta() {

        //could be buying already and inventory is 0
        targetStockList.forEach(symb -> {
            if (symbolPosMap.getOrDefault(symb, Decimal.ZERO).isZero()
                    && stockStatusMap.get(symb) == StockStatus.UNKNOWN) {

                stockStatusMap.put(symb, StockStatus.NO_INVENTORY);
            }
        });

        pr("calculate percentile", LocalDateTime.now().format(f1), "target list size", targetStockList.size());
//        pr("calculate percentile", targetStockList.size());

        targetStockList.forEach(symb -> {
//            pr("target stock", symb);
//            pr("1. three day data, symb", symb, threeDayData.get(symb));
            pr("three day data contains ", symb, threeDayData.containsKey(symb));
//            pr("three day data is empty ", threeDayData);
            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {
//                pr("map", threeDayData.get(symb));
                ConcurrentSkipListMap<LocalDateTime, SimpleBar> threeDayMap = threeDayData.get(symb);
                ConcurrentSkipListMap<LocalDateTime, SimpleBar> oneDayMap =
                        new ConcurrentSkipListMap<>(Optional.of(threeDayData.get(symb)
                                .tailMap(TODAY_MARKET_START_TIME)).get());

//                pr("one day data, symb", symb, oneDayMap.size(), !oneDayMap.isEmpty() ? oneDayMap : "");
//                double maxValue = m.entrySet().stream().mapToDouble(b -> b.getValue().getHigh()).max().getAsDouble();
//                double minValue = m.entrySet().stream().mapToDouble(b -> b.getValue().getLow()).min().getAsDouble();
//                double last = m.lastEntry().getValue().getClose();
//                double percentile = r((last - minValue) / (maxValue - minValue) * 100);
                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb)
                        .tailMap(TODAY_MARKET_START_TIME));
                pr("three day, one day", threeDayPercentile, oneDayPercentile);

//                pr("today market start time ", TODAY_MARKET_START_TIME);
                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                pr(symb, "time stock percentile", "from ", threeDayMap.firstKey().format(f1), LocalDateTime.now().format(f1),
                        symb, "3d p%:", round(threeDayPercentile), "1d p%:", round(oneDayPercentile));
            }

            if (ytdDayData.containsKey(symb) && !ytdDayData.get(symb).isEmpty()) {
//                pr("hist data", ytdDayData.get(symb));
//                ConcurrentSkipListMap<LocalDate, SimpleBar> m = ytdDayData.get(symb);
                double lastYearClose = ytdDayData.get(symb).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                double returnOnYear = ytdDayData.get(symb).lastEntry().getValue().getClose() / lastYearClose - 1;
                lastYearCloseMap.put(symb, lastYearClose);
                pr("ytd return", symb, returnOnYear);
            }
//            pr("symbolconidmap", symbolConIDMap);
        });

        //update entire portfolio delta
        aggregateDelta = targetStockList.stream().mapToDouble(s -> symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue()
                * latestPriceMap.getOrDefault(s, 0.0)).sum();

        //update individual stock delta
        targetStockList.forEach((s) -> symbolDeltaMap.put(s, symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue()
                * latestPriceMap.getOrDefault(s, 0.0)));

        pr("aggregate Delta", aggregateDelta, "each delta", symbolDeltaMap);
    }


    public static Decimal getTradeSizeFromPrice(double price) {
        if (price < 100) {
            return Decimal.get(20);
        }
        return Decimal.get(10);
    }

    //Trade
    private static void inventoryAdder(Contract ct, double price, LocalDateTime t, double perc3d, double perc1d) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);
        StockStatus status = stockStatusMap.getOrDefault(symbol, StockStatus.UNKNOWN);

        pr("inventory adder", symbol, pos, status);

//        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();
//        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();

        if (pos.isZero() && status == StockStatus.NO_INVENTORY) {
//            Decimal defaultS = Decimal.get(10);
            Decimal defaultS = getTradeSizeFromPrice(price);
//            addedMap.put(symbol, new AtomicBoolean(true));
            int id = tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price)));
            Order o = placeBidLimitTIF(bidPrice, defaultS, DAY);
            orderMap.put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, StockStatus.BUYING_INVENTORY));
            outputToSymbolFile(symbol, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symbol, str(o.orderId(), id, "BUY INVENTORY:", "price:", bidPrice,
                    orderMap.get(id), "p/b/a", price,
                    getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol),
                    "3d perc/1d perc", perc3d, perc1d), outputFile);
            stockStatusMap.put(symbol, StockStatus.BUYING_INVENTORY);
        }
    }


    private static void inventoryCutter(Contract ct, double price, LocalDateTime t) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);

        StockStatus status = stockStatusMap.getOrDefault(symbol, StockStatus.UNKNOWN);

        if (pos.longValue() > 0 && status == StockStatus.HAS_INVENTORY) {
            int id = tradeID.incrementAndGet();
            double cost = costMap.getOrDefault(symbol, Double.MAX_VALUE);
            double offerPrice = r(Math.max(askMap.getOrDefault(symbol, price),
                    costMap.getOrDefault(symbol, Double.MAX_VALUE) * PROFIT_LEVEL));

            Order o = placeOfferLimitTIF(offerPrice, pos, DAY);
            orderMap.put(id, new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, StockStatus.SELLING_INVENTORY));
            outputToSymbolFile(symbol, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symbol, str(o.orderId(), id, "SELL INVENTORY:"
                    , "offer price:", offerPrice, "cost:", cost, Optional.ofNullable(orderMap.get(id))
                    , "price/bid/ask:", price,
                    getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol)), outputFile);
            stockStatusMap.put(symbol, StockStatus.SELLING_INVENTORY);
        }
    }


    //request realized pnl

    /**
     * Execution details
     */
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {

        tradeKeyExecutionMap.put(tradeKey, execution);

        pr("tradeReport:", tradeKey, ibContractToSymbol(contract), "time, side, price, shares, avgPrice:",
                execution.time(), execution.side(), execution.price(), execution.shares(),
                execution.avgPrice(), Optional.ofNullable(orderMap.get(execution.orderId())).map(e -> e.getSymbol()).orElse(""));

        outputToFile(str("tradeReport", ibContractToSymbol(contract), "time, side, price, shares, avgPrice:",
                execution.time(), execution.side(), execution.price(), execution.shares(),
                execution.avgPrice()), outputFile);
    }

    @Override
    public void tradeReportEnd() {
        pr("trade report end");
    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {

        String symb = Optional.ofNullable(tradeKeyExecutionMap.get(tradeKey)).map(exec ->
                orderMap.get(exec.orderId())).map(OrderAugmented::getSymbol).orElse("");

        pr("commission report", "symb:", symb, "commission", commissionReport.commission(),
                "realized pnl", commissionReport.realizedPNL());

        outputToFile(str("commission report", "symb:", symb, "commission", commissionReport.commission(),
                "realized pnl", commissionReport.realizedPNL()), outputFile);
    }


    public static void main(String[] args) {
        Tester test1 = new Tester();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(Tester::computePercentileAndDelta, 10L, 10L, TimeUnit.SECONDS);
//        es.scheduleAtFixedRate(Tester::reqHoldings, 10L, 10L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pr("closing hook ");
            outputToFile(str("Ending Trader ", LocalDateTime.now().format(f1)), outputFile);
            orderMap.forEach((k, v) -> {
                if (v.getAugmentedOrderStatus() != OrderStatus.Filled &&
                        v.getAugmentedOrderStatus() != OrderStatus.PendingCancel) {

                    pr("unexecuted orders:", v.getSymbol(), str("Shutdown status",
                            LocalDateTime.now().format(TradingConstants.f1), v.getAugmentedOrderStatus(), v));

                    outputToSymbolFile(v.getSymbol(), str("Shutdown status",
                            LocalDateTime.now().format(TradingConstants.f1), v.getAugmentedOrderStatus(), v), outputFile);
                }
            });
            apiController.cancelAllOrders();
        }));
    }

    //Open Orders
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        outputToFile(str(ibContractToSymbol(contract), "order", order, "orderstate:", orderState), outputFile);
    }

    @Override
    public void openOrderEnd() {

    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        outputToFile(str("orderId", orderId, "OrderStatus", status, "filled",
                filled, "remaining", remaining), outputFile);
    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToFile(str("order id", orderId, "errorCode", errorCode, "msg:", errorMsg), outputFile);
    }
    //open orders end
}
