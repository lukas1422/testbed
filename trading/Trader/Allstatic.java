package Trader;

import TradeType.TradeBlock;
import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import enums.InventoryStatus;
import historical.Request;
import utility.TradingUtility;
import utility.Utility;

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

import static utility.TradingUtility.getESTLocalDateTimeNow;
import static utility.TradingUtility.getTradeDate;
import static utility.Utility.*;

//test
public class Allstatic {
    public static final LocalDate MONDAY_OF_WEEK = getMondayOfWeek(LocalDateTime.now());
    public static final LocalDate LAST_YEAR_DAY = getYearBeginMinus1Day();
    static final LocalDateTime TODAY_MARKET_START_TIME =
            LocalDateTime.of(getESTLocalDateTimeNow().toLocalDate(), ltof(9, 30));
    static final double DELTA_LIMIT = 10000;
    static final double DELTA_LIMIT_EACH_STOCK = 2000;
    public static volatile Map<String, Double> priceMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> openMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> closeMap = new ConcurrentHashMap<>();
    public static AtomicInteger GLOBAL_REQ_ID = new AtomicInteger(30000);
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
    //    static volatile NavigableMap<Integer, OrderAugmented> orderSubmitted = new ConcurrentSkipListMap<>();
    protected static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>> orderSubmitted = new ConcurrentHashMap<>();
    protected static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>> orderStatusMap = new ConcurrentHashMap<>();
    //    static volatile NavigableMap<String, List<Order>> openOrders = new ConcurrentSkipListMap<>();
    static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>> openOrders = new ConcurrentSkipListMap<>();
//    static volatile Map<String, InventoryStatus> inventoryStatusMap = new ConcurrentHashMap<>();
    static volatile AtomicInteger tradeID = new AtomicInteger(100);
    static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    static volatile double aggregateDelta = 0.0;
    //data
    static Map<String, Double> latestPriceMap = new ConcurrentHashMap<>();
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
    static Map<String, ExecutionAugmented> tradeKeyExecutionMap = new ConcurrentHashMap<>();

    static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);
    static Map<String, LocalDateTime> lastOrderTime = new ConcurrentHashMap<>();

    public static Contract getActiveA50Contract() {
        Contract ct = new Contract();
        ct.symbol("XINA50");
        ct.exchange("SGX");
        ct.secType(Types.SecType.FUT);
        pr("A50 front expiry ", TradingUtility.getXINA50FrontExpiry());
        ct.lastTradeDateOrContractMonth(TradingUtility.getXINA50FrontExpiry().format(futExpPattern));
        ct.currency("USD");
        return ct;

//        long daysUntilFrontExp = ChronoUnit.DAYS.between(LocalDate.now(), getXINA50FrontExpiry());
//        pr(" **********  days until expiry **********", daysUntilFrontExp, getXINA50FrontExpiry());
//        if (daysUntilFrontExp <= 1) {
//            pr(" using back fut ");
//            return getBackFutContract();
//        } else {
//            pr(" using front fut ");
//            return getFrontFutContract();
//        }
    }

    public static Contract getActiveBTCContract() {
        Contract ct = new Contract();
        ct.symbol("GXBT");
        ct.exchange("CFECRYPTO");
        ct.secType(Types.SecType.FUT);
        pr("BTC expiry ", TradingUtility.getActiveBTCExpiry());
        pr("BTC expiry pattern ", TradingUtility.getActiveBTCExpiry().format(futExpPattern2));
        ct.lastTradeDateOrContractMonth(TradingUtility.getActiveBTCExpiry().format(futExpPattern2));
//        ct.lastTradeDateOrContractMonth("20190");
        ct.currency("USD");
        return ct;
    }

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
//        pr("three day data today so far ", symbol, ld, close);
        liveData.get(symbol).put(ld, close);
    }
}
