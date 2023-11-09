package Trader;

import api.OrderAugmented;
import api.TradingConstants;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
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

public class Tester implements LiveHandler, ApiController.IPositionHandler {

    private static ApiController apDev;
    private static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);

    Contract gjs = generateHKStockContract("388");
    Contract xiaomi = generateHKStockContract("1810");
    Contract wmt = generateUSStockContract("WMT");


    static volatile NavigableMap<Integer, OrderAugmented> orderMap = new ConcurrentSkipListMap<>();
    static volatile AtomicInteger tradeID = new AtomicInteger(100);

    //files
//    private static File outputFile = new File(TradingConstants.GLOBALPATH + "output.txt");
    static File testOutputFile = new File("trading/TradingFiles/output");
    //File f = new File("trading/TradingFiles/output");


    //data
    private static volatile TreeSet<Contract> targetStockList = new TreeSet<>(Comparator.comparing(k -> k.symbol()));
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData = new ConcurrentSkipListMap<>();
    private static Map<String, Double> lastMap = new ConcurrentHashMap<>();
    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private static Map<String, Double> percentileMap = new ConcurrentHashMap<>();


    //historical data
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>> ytdDayData
            = new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> todayData
            = new ConcurrentSkipListMap<>(String::compareTo);


    //position
    private volatile static Map<Contract, Decimal> contractPosMap =
            new ConcurrentSkipListMap<>(Comparator.comparing(Utility::ibContractToSymbol));

    private volatile static Map<String, Decimal> symbolPosMap = new ConcurrentSkipListMap<>(String::compareTo);

    private static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);

    //avoid too many requests at once, only 50 requests allowed at one time.
    private static Semaphore histSemaphore = new Semaphore(45);

    //Trade
    private static volatile Map<String, AtomicBoolean> addedMap = new ConcurrentHashMap<>();
    private static volatile Map<String, AtomicBoolean> liquidatedMap = new ConcurrentHashMap<>();
    private static volatile Map<String, AtomicBoolean> tradedMap = new ConcurrentHashMap<>();


    private Tester() {
        pr("initializing...");
        registerContract(wmt);
//        outputToFile(str("Start time is", LocalDateTime.now()), outputFile);
        File f = new File("trading/TradingFiles/output");
        pr(f.getAbsolutePath());

        outputToFile("test", f);

    }


    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new Utility.DefaultLogger(), new Utility.DefaultLogger());
        apDev = ap;
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
        targetStockList.forEach(ct -> {
            CompletableFuture.runAsync(() -> {
                //                histSemaphore.acquire();
                reqHistDayData(apDev, ibStockReqId.addAndGet(5), histCompatibleCt(ct),
                        Tester::todaySoFar, 2, Types.BarSize._1_min);
            });
            CompletableFuture.runAsync(() -> {
                //                histSemaphore.acquire();
                reqHistDayData(apDev, ibStockReqId.addAndGet(5), histCompatibleCt(ct), Tester::ytdOpen,
                        Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day);
            });

        });

        reqHoldings(apDev);
        Executors.newScheduledThreadPool(10).schedule(() -> apDev.reqPositions(this), 500,
                TimeUnit.MILLISECONDS);
        req1ContractLive(apDev, liveCompatibleCt(wmt), this, false);
    }

    private static void registerContract(Contract ct) {
        targetStockList.add(ct);
        contractPosMap.put(ct, Decimal.ZERO);
        symbolPosMap.put(ibContractToSymbol(ct), Decimal.ZERO);
        String symbol = ibContractToSymbol(ct);
        if (!liveData.containsKey(symbol)) {
            liveData.put(symbol, new ConcurrentSkipListMap<>());
        }
    }

    private static void todaySoFar(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = Utility.ibContractToSymbol(c);

        if (!todayData.containsKey(symbol)) {
            todayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        if (!liveData.containsKey(symbol)) {
            liveData.put(symbol, new ConcurrentSkipListMap<>());
        }


        pr("test data today", "date", date, open, high, low, close);
        if (!date.startsWith("finished")) {
            LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
                    TimeZone.getDefault().toZoneId());
            pr("ld is", ld);
            todayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
            liveData.get(symbol).put(ld, close);
        }
    }

    private static void ytdOpen(Contract c, String date, double open, double high, double low, double close, long volume) {

        pr("test if called in ytdopen");
        String symbol = Utility.ibContractToSymbol(c);
        pr("symb is", symbol);

        if (!ytdDayData.containsKey(symbol)) {
            ytdDayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        pr("date", date, open, high, low, close);
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

    private void reqHoldings(ApiController ap) {
        pr("req holdings ");
        ap.reqPositions(this);
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symbol = ibContractToSymbol(ct);

        LocalDate prevMonthCutoff = getPrevMonthCutoff(ct, getMonthBeginMinus1Day(t.toLocalDate()));
        LocalDateTime dayStartTime = LocalDateTime.of(t.toLocalDate(), ltof(9, 30, 0));
        LocalDate previousQuarterCutoff = getQuarterBeginMinus1Day(t.toLocalDate());
        LocalDate previousHalfYearCutoff = getHalfYearBeginMinus1Day(t.toLocalDate());

        pr("live price", tt, symbol, price, t);

        switch (tt) {
            case LAST:
                lastMap.put(symbol, price);
                liveData.get(symbol).put(t, price);

                //trade logic
                if (percentileMap.containsKey(symbol) && percentileMap.get(symbol) < 10
                        && !symbolPosMap.get(symbol).isZero()) {
                    outputToFile(str("can trade", t, symbol, percentileMap.get(symbol)), testOutputFile);
                    inventoryAdder(ct, price, t, percentileMap.getOrDefault(symbol, Double.MAX_VALUE));
                }

            case BID:
                bidMap.put(symbol, price);
                break;
            case ASK:
                askMap.put(symbol, price);
                break;
        }
    }

    @Override
    public void handleVol(TickType tt, String symbol, double vol, LocalDateTime t) {
        pr("live vol", symbol, tt, vol, t);

    }

    @Override
    public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {
        pr("live generic", symbol, tt, value, t);

    }

    @Override
    public void handleString(TickType tt, String symbol, String str, LocalDateTime t) {
        pr("live string", symbol, tt, str, t);

    }
    //livedata end


    //position start
    @Override
    public void position(String account, Contract contract, Decimal position, double avgCost) {
        if (!contract.symbol().equals("USD")) {
            contractPosMap.put(contract, position);
            symbolPosMap.put(ibContractToSymbol(contract), position);
        }
//        contractPosMap.entrySet().stream().forEach(e -> pr(e.getKey().symbol(), e.getValue()));
//     pr("contract map", contractPosMap);
    }

    @Override
    public void positionEnd() {
        pr("position end");
    }

    static void calculatePercentile() {
        pr("calculate percentile", LocalDateTime.now(), targetStockList.size());
//        pr("calculate percentile", targetStockList.size());

        targetStockList.forEach(ct -> {
            String symb = ibContractToSymbol(ct);
            pr("stock", symb);

            if (todayData.containsKey(symb) && !todayData.get(symb).isEmpty()) {
                pr("map", todayData.get(symb));
                ConcurrentSkipListMap<LocalDateTime, SimpleBar> m = todayData.get(symb);
                double maxValue = m.entrySet().stream().mapToDouble(b -> b.getValue().getHigh()).max().getAsDouble();
                double minValue = m.entrySet().stream().mapToDouble(b -> b.getValue().getLow()).min().getAsDouble();
                double last = m.lastEntry().getValue().getClose();

                double percentile = r((last - minValue) / (maxValue - minValue) * 100);
                percentileMap.put(symb, percentile);
                pr("time, stock percentile", LocalDateTime.now(), symb, percentile, "max min last", maxValue, minValue, last);
            }

            if (ytdDayData.containsKey(symb) && !ytdDayData.get(symb).isEmpty()) {
//                pr("hist data", ytdDayData.get(symb));
                ConcurrentSkipListMap<LocalDate, SimpleBar> m = ytdDayData.get(symb);
                double lastYearClose = ytdDayData.get(symb).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                double returnOnYear = ytdDayData.get(symb).lastEntry().getValue().getClose()
                        / lastYearClose - 1;
                pr("ytd return", symb, returnOnYear);
            }
        });
    }

    //Trade
    private static void inventoryAdder(Contract ct, double price, LocalDateTime t, double percentile) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);

        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();
        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();

        if (pos.longValue() < 10 && !added && percentile < 10) {
            Decimal defaultS = Decimal.get(10);
            addedMap.put(symbol, new AtomicBoolean(true));
            int id = tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price)));
            Order o = placeBidLimitTIF(bidPrice, defaultS, DAY);
            orderMap.put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
            placeOrModifyOrderCheck(apDev, ct, o, new PatientOrderHandler(id));
            outputToSymbolFile(symbol, str("********", t.format(f1)), testOutputFile);
            outputToSymbolFile(symbol, str(o.orderId(), id, "ADDER BUY:",
                    orderMap.get(id), "p/b/a", price,
                    getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol)), testOutputFile);

        }
    }

    private static void inventoryCutter(Contract ct, double price, LocalDateTime t, double percentile) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);

        Decimal defaultS = Decimal.get(10);

        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();
        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();

        if (!added && !liquidated && percentile > 90) {
            addedMap.put(symbol, new AtomicBoolean(true));
            int id = tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price)));

            Order o = placeOfferLimitTIF(bidPrice, defaultS, DAY);
            orderMap.put(id, new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
            placeOrModifyOrderCheck(apDev, ct, o, new PatientOrderHandler(id));
            outputToSymbolFile(symbol, str("********", t.format(f1)), testOutputFile);
            outputToSymbolFile(symbol, str(o.orderId(), id, "ADDER SELLER:",
                    orderMap.get(id), "p/b/a", price,
                    getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol)), testOutputFile);
        }
    }


    public static void main(String[] args) {
        Tester test1 = new Tester();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(Tester::calculatePercentile, 10L, 10L, TimeUnit.SECONDS);
//        es.scheduleAtFixedRate(Tester::reqHoldings, 10L, 10L, TimeUnit.SECONDS);
    }
}
