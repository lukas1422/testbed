package Trader;

import TradeType.TradeBlock;
import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import historical.Request;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static Trader.TradingUtility.*;
import static utility.Utility.*;

//test
public class Allstatic {
    public static final LocalDate MONDAY_OF_WEEK = getMondayOfWeek(LocalDateTime.now());
    public static final LocalDate LAST_YEAR_DAY = getYearBeginMinus1Day();
    static final LocalDateTime TRADING_START_TIME =
            LocalDateTime.of(getESTLocalDateTimeNow().toLocalDate(), ltof(9, 30));
    static final LocalDateTime PERCENTILE_START_TIME =
            LocalDateTime.of(getESTLocalDateTimeNow().toLocalDate(), ltof(7, 30));
    static final double DELTA_LIMIT = 10000;
    static final double DELTA_LIMIT_EACH_STOCK = 4000;
    public static volatile Map<String, Double> priceMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> openMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> closeMap = new ConcurrentHashMap<>();
    //    public static AtomicInteger GLOBAL_REQ_ID = new AtomicInteger(30000);
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBar = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> priceMapBarDetail
            = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBarYtd = new ConcurrentHashMap<>();

    public static volatile Map<Integer, Request> globalRequestMap = new ConcurrentHashMap<>();
    public volatile static Map<String, Decimal> currentPositionMap = new TreeMap<>(String::compareTo);
    public volatile static Map<String, Integer> openPositionMap = new HashMap<>();
    public volatile static Map<String, ConcurrentSkipListMap<LocalTime, TradeBlock>> tradesMap = new ConcurrentHashMap<>();
    public static volatile NavigableMap<Integer, OrderAugmented> globalIdOrderMap =
            new ConcurrentSkipListMap<>();
    public static volatile List<String> symbolNames = new ArrayList<>(1000);
    public static volatile LocalDate currentTradingDate = getTradeDate(LocalDateTime.now());
    public static File outputFile = new File("trading/TradingFiles/output");
    protected static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>> orderSubmitted = new ConcurrentHashMap<>();
    protected static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>> orderStatusMap = new ConcurrentHashMap<>();
    static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>> openOrders = new ConcurrentSkipListMap<>();
    static volatile AtomicInteger tradeID = new AtomicInteger(100);
    static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    static volatile double aggregateDelta = 0.0;
    //data
    static Map<String, Double> latestPriceMap = new ConcurrentHashMap<>();
    static Map<String, LocalTime> latestPriceTimeMap = new ConcurrentHashMap<>();

    static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    static Map<String, Double> askMap = new ConcurrentHashMap<>();
    static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData
            = new ConcurrentSkipListMap<>();
    static volatile Map<String, Double> lastYearCloseMap = new ConcurrentHashMap<>();
    static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> threeDayData
            = new ConcurrentSkipListMap<>(String::compareTo);
    //historical data
    static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>> ytdDayData
            = new ConcurrentSkipListMap<>(String::compareTo);
    volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();
    volatile static Map<String, Decimal> symbolPosMap = new ConcurrentSkipListMap<>(String::compareTo);
    volatile static Map<String, Double> symbolDeltaMap = new ConcurrentSkipListMap<>(String::compareTo);
    static Map<String, Double> threeDayPctMap = new ConcurrentHashMap<>();
    static Map<String, Double> oneDayPctMap = new ConcurrentHashMap<>();
    static Map<String, Integer> symbolConIDMap = new ConcurrentHashMap<>();
    static Map<String, List<ExecutionAugmented>> tradeKeyExecutionMap = new ConcurrentHashMap<>();

    static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);
//    static Map<String, LocalDateTime> lastOrderTime = new ConcurrentHashMap<>();

    // this gets YTD return
    static void ytdOpen(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);

        if (!ytdDayData.containsKey(symbol)) {
            ytdDayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
        ytdDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
    }

    static void todaySoFar(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);
        LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
                TimeZone.getTimeZone("America/New_York").toZoneId());

        threeDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
        liveData.get(symbol).put(ld, close);
    }
}
