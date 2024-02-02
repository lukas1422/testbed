package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import historical.Request;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static Trader.TradingUtility.*;
import static java.lang.Math.pow;
import static utility.Utility.*;

//test
public class Allstatic {
    //    public static final LocalDate MONDAY_OF_WEEK = getMondayOfWeek(LocalDateTime.now());
    public static final LocalDate LAST_YEAR_DAY = getYearBeginMinus1Day();
    static final LocalDateTime TODAY930 =
            LocalDateTime.of(getESTDateTimeNow().toLocalDate(), ltof(9, 30));
    static final LocalDateTime TODAY230 =
            LocalDateTime.of(getESTDateTimeNow().toLocalDate(), ltof(2, 30));
    public static volatile Map<String, Double> priceMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> openMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> closeMap = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBar = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> priceMapBarDetail
            = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBarYtd = new ConcurrentHashMap<>();
    public static volatile Map<Integer, Request> globalRequestMap = new ConcurrentHashMap<>();
    public volatile static Map<String, Decimal> currentPositionMap = new TreeMap<>(String::compareTo);
    //    public volatile static Map<String, Integer> openPositionMap = new HashMap<>();
//    public volatile static Map<String, ConcurrentSkipListMap<LocalTime, TradeBlock>> tradesMap = new ConcurrentHashMap<>();
    public static volatile NavigableMap<Integer, OrderAugmented> globalIdOrderMap = new ConcurrentSkipListMap<>();
    public static volatile List<String> symbolNames = new ArrayList<>(1000);
    public static volatile LocalDate currentTradingDate = getTradeDate(LocalDateTime.now());
    public static File outputFile = new File("trading/TradingFiles/output");
    protected static volatile Map<String, ConcurrentSkipListMap<Integer, OrderAugmented>> orderSubmitted = new ConcurrentHashMap<>();
    protected static volatile Map<String, ConcurrentSkipListMap<Integer, OrderStatus>>
            orderStatus = new ConcurrentHashMap<>();
    static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>>
            openOrders = new ConcurrentSkipListMap<>();
    //    static volatile AtomicInteger tradeID = new AtomicInteger(1200);
    //    static volatile AtomicInteger tradeID = new AtomicInteger(getNewTradeID());
    static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);

    static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> liveData
            = new ConcurrentSkipListMap<>();
    static volatile Map<String, Double> lastYearCloseMap = new ConcurrentHashMap<>();
    static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>>
            twoDayData = new ConcurrentSkipListMap<>(String::compareTo);

    static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);
    // static Map<String, LocalDateTime> lastOrderTime = new ConcurrentHashMap<>();

    // this gets YTD return

    static int getSessionMasterTradeID() {
        LocalDateTime t = getESTDateTimeNow();
//        return (t.getYear()-2000)* pow(10,10)+t.getMonthValue()*pow(10,8)+
//        pr(t, "get new trade id", t.getHour() * pow(10, 4), t.getMinute() * 100, t.getSecond(), "ID:",
//                (int) (t.getHour() * pow(10, 4) + t.getMinute() * 100 + t.getSecond()));
//        return (int) (t.getHour() * pow(10, 4) + t.getMinute() * 100 + t.getSecond());
        pr("year is ", (t.getYear() - 2000) * pow(10, 7), "month:", t.getMonthValue() * pow(10, 5),
                "day:", t.getDayOfMonth() * pow(10, 3));
        int id = (int) ((t.getYear() - 2000) * pow(10, 7) + t.getMonthValue() * pow(10, 5) +
                t.getDayOfMonth() * pow(10, 3) +
                Math.max(0, Duration.between(TODAY930, getESTDateTimeNow()).toMinutes()) + 1);
//        int id = (int) (t.getYear() * pow(10, 7) + t.getMonthValue() * pow(10, 5) + t.getDayOfMonth() * pow(10, 3) + 1);
        outputToGeneral("MasterTradeID is:", id);
        return id;
    }

    static void todaySoFar(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);
        LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000),
                TimeZone.getTimeZone("America/New_York").toZoneId());

        twoDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
        liveData.get(symbol).put(ld, close);
    }
}
