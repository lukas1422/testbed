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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.f1;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class Tester implements LiveHandler, ApiController.IPositionHandler, ApiController.ITradeReportHandler {

    private static ApiController apiController;
    private static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    static volatile AtomicInteger tradeID = new AtomicInteger(100);
    static volatile AtomicInteger allOtherReqID = new AtomicInteger(10000);

    static volatile double aggregateDelta = 0.0;


    Contract gjs = generateHKStockContract("388");
    Contract xiaomi = generateHKStockContract("1810");
    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");

    private static final double PROFIT_LEVEL = 1.004;
    private static final double DELTA_LIMIT = 10000;
    private static final double DELTA_LIMIT_EACH_STOCK = 2000;


    static volatile NavigableMap<Integer, OrderAugmented> orderMap = new ConcurrentSkipListMap<>();


    //files
//    private static File outputFile = new File(TradingConstants.GLOBALPATH + "output.txt");
    static File outputFile = new File("trading/TradingFiles/output");
    //File f = new File("trading/TradingFiles/output");

    static volatile Map<String, StockStatus> stockStatusMap = new ConcurrentHashMap<>();


    //data
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData = new ConcurrentSkipListMap<>();
    private static Map<String, Double> lastMap = new ConcurrentHashMap<>();
    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private static Map<String, Double> threeDayPctMap = new ConcurrentHashMap<>();
    private static Map<String, Double> oneDayPctMap = new ConcurrentHashMap<>();


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

    public static final LocalDateTime TODAY_MARKET_START_TIME =
            LocalDateTime.of(LocalDateTime.now().toLocalDate(), LocalTime.of(9, 30));


    private Tester() {
        pr("initializing...");
        registerContract(wmt);
        registerContract(pg);
//        outputToFile(str("Start time is", LocalDateTime.now()), outputFile);
        File f = new File("trading/TradingFiles/output");
        pr(f.getAbsolutePath());

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
            pr(" Latch counted down 4001 " + LocalTime.now());
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        if (!connectionStatus) {
            pr(" using port 7496");
            ap.connect("127.0.0.1", 7496, 5, "");
            l.countDown();
            pr(" Latch counted down 7496" + LocalTime.now());
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
            pr("target stock symb ", symb);
            Contract c = generateUSStockContract(symb);
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
        Executors.newScheduledThreadPool(10).schedule(() -> apiController.reqPositions(this), 500,
                TimeUnit.MILLISECONDS);
//        req1ContractLive(apDev, liveCompatibleCt(wmt), this, false);
        apiController.reqExecutions(new ExecutionFilter(), this);
//        apiController.reqPnLSingle();
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        targetStockList.add(symb);
        symbolPosMap.put(symb, Decimal.ZERO);
        stockStatusMap.put(symb, StockStatus.UNKNOWN);

        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    private static void todaySoFar(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = Utility.ibContractToSymbol(c);

        if (!threeDayData.containsKey(symbol)) {
            threeDayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        if (!liveData.containsKey(symbol)) {
            liveData.put(symbol, new ConcurrentSkipListMap<>());
        }

        if (!date.startsWith("finished")) {
            LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
                    TimeZone.getDefault().toZoneId());
            threeDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
            liveData.get(symbol).put(ld, close);
        }
    }

    private static void ytdOpen(Contract c, String date, double open, double high, double low, double close, long volume) {

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

//    private void reqHoldings(ApiController ap) {
//        pr("req holdings ");
//        ap.reqPositions(this);
//    }

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
                lastMap.put(symb, price);
                liveData.get(symb).put(t, price);
                pr("inventory status", symb, stockStatusMap.get(symb));

                if (!symbolPosMap.get(symb).isZero()) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }


                //trade logic
//                if (lastYearCloseMap.getOrDefault(symbol, 0.0) > price && percentileMap.containsKey(symbol)) {
                if (threeDayPctMap.containsKey(symb) && oneDayPctMap.containsKey(symb)) {

                    if (aggregateDelta < DELTA_LIMIT
                            && symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) < DELTA_LIMIT_EACH_STOCK) {
                        if (threeDayPctMap.get(symb) < 40 && oneDayPctMap.get(symb) < 10 && symbolPosMap.get(symb).isZero()) {
                            //outputToFile(str("can trade", t, symbol, percentileMap.get(symbol)), testOutputFile);
                            inventoryAdder(ct, price, t);
//                    } else if (percentileMap.get(symbol) > 90 && !symbolPosMap.get(symbol).isZero()) {
                        }
                    }

                    if (symbolPosMap.get(symb).longValue() > 0) {
                        if (costMap.containsKey(symb)) {
                            pr(symb, "price/cost", price / costMap.get(symb));
                            if (price / costMap.get(symb) > PROFIT_LEVEL) {
                                inventoryCutter(ct, price, t, threeDayPctMap.getOrDefault(symb, 0.0));
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

            es.schedule(() -> {
                pr("Position end: requesting live for fut:", symb);
                req1ContractLive(apiController, liveCompatibleCt(generateUSStockContract(symb)), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    static void computePercentileAndDelta() {
        pr("calculate percentile", LocalDateTime.now(), "size", targetStockList.size());
//        pr("calculate percentile", targetStockList.size());

        targetStockList.forEach(symb -> {
//            pr("target stock", symb);

            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {
//                pr("map", todayData.get(symb));
                ConcurrentSkipListMap<LocalDateTime, SimpleBar> m = threeDayData.get(symb);
//                double maxValue = m.entrySet().stream().mapToDouble(b -> b.getValue().getHigh()).max().getAsDouble();
//                double minValue = m.entrySet().stream().mapToDouble(b -> b.getValue().getLow()).min().getAsDouble();
//                double last = m.lastEntry().getValue().getClose();
//                double percentile = r((last - minValue) / (maxValue - minValue) * 100);
                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb)
                        .tailMap(TODAY_MARKET_START_TIME));
                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                pr("time stock percentile", "from ", m.firstKey().format(f1), LocalDateTime.now().format(f1),
                        symb, "p%:", threeDayPercentile);
            }

            if (ytdDayData.containsKey(symb) && !ytdDayData.get(symb).isEmpty()) {
//                pr("hist data", ytdDayData.get(symb));
//                ConcurrentSkipListMap<LocalDate, SimpleBar> m = ytdDayData.get(symb);
                double lastYearClose = ytdDayData.get(symb).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                double returnOnYear = ytdDayData.get(symb).lastEntry().getValue().getClose() / lastYearClose - 1;
                lastYearCloseMap.put(symb, lastYearClose);
                pr("ytd return", symb, returnOnYear);
            }
        });

        //update entire portfolio delta
        aggregateDelta = targetStockList.stream().mapToDouble(s -> symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue()
                * lastMap.getOrDefault(s, 0.0)).sum();

        //update individual stock delta
        targetStockList.forEach((s) -> symbolDeltaMap.put(s, symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue()
                * lastMap.getOrDefault(s, 0.0)));

    }

    static void calculateAggregateDelta() {
    }

    //Trade
    private static void inventoryAdder(Contract ct, double price, LocalDateTime t) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);
        StockStatus status = stockStatusMap.getOrDefault(symbol, StockStatus.UNKNOWN);

//        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();
//        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();

        if (pos.isZero() && status == StockStatus.NO_INVENTORY) {
            Decimal defaultS = Decimal.get(10);
//            addedMap.put(symbol, new AtomicBoolean(true));
            int id = tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price)));
            Order o = placeBidLimitTIF(bidPrice, defaultS, DAY);
            orderMap.put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, StockStatus.BUYING_INVENTORY));
            outputToSymbolFile(symbol, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symbol, str(o.orderId(), id, "BUY INVENTORY:", "price:", bidPrice,
                    orderMap.get(id), "p/b/a", price,
                    getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol)), outputFile);
            stockStatusMap.put(symbol, StockStatus.BUYING_INVENTORY);
        }
    }

    private static void inventoryCutter(Contract ct, double price, LocalDateTime t, double percentile) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);

//        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();
        StockStatus status = stockStatusMap.getOrDefault(symbol, StockStatus.UNKNOWN);

//        if (!liquidated && percentile > 90 && pos.longValue() > 0) {
        if (pos.longValue() > 0 && status == StockStatus.HAS_INVENTORY) {
//            liquidatedMap.put(symbol, new AtomicBoolean(true));
            int id = tradeID.incrementAndGet();
//            double offerPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price)));
            double cost = costMap.getOrDefault(symbol, Double.MAX_VALUE);
            double offerPrice = r(Math.max(askMap.getOrDefault(symbol, price),
                    costMap.getOrDefault(symbol, Double.MAX_VALUE) * PROFIT_LEVEL));

            Order o = placeOfferLimitTIF(offerPrice, pos, DAY);
            orderMap.put(id, new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, StockStatus.SELLING_INVENTORY));
            outputToSymbolFile(symbol, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symbol, str(o.orderId(), id, "SELL INVENTORY:"
                    , "offerprice:", offerPrice, "cost:", cost, orderMap.get(id), "p/b/a", price,
                    getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol)), outputFile);
            stockStatusMap.put(symbol, StockStatus.SELLING_INVENTORY);
        }
    }

    //request realized pnl


    //request exe details


    public static void main(String[] args) {
        Tester test1 = new Tester();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(Tester::computePercentileAndDelta, 10L, 10L, TimeUnit.SECONDS);
//        es.scheduleAtFixedRate(Tester::reqHoldings, 10L, 10L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pr("closing hook ");
            outputToFile(str("Ending Trader ", LocalDateTime.now()), outputFile);
            orderMap.forEach((k, v) -> {
                if (v.getAugmentedOrderStatus() != OrderStatus.Filled &&
                        v.getAugmentedOrderStatus() != OrderStatus.PendingCancel) {
                    outputToSymbolFile(v.getSymbol(), str("Shutdown status",
                            LocalDateTime.now().format(TradingConstants.f1), v.getAugmentedOrderStatus(), v), outputFile);
                }
            });
            apiController.cancelAllOrders();
        }));
    }


    //execution report
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        pr("tradeReport:", tradeKey, ibContractToSymbol(contract), "time, side, price, shares, avgeprice:",
                execution.time(), execution.side(), execution.price(), execution.shares(),
                execution.avgPrice());
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
        pr("commission report", "Tradekey:", tradeKey, "commission", commissionReport.commission(),
                "realized pnl", commissionReport.realizedPNL());

        outputToFile(str("commission report", "Tradekey:", tradeKey, "commission", commissionReport.commission(),
                "realized pnl", commissionReport.realizedPNL()), outputFile);
    }
}
