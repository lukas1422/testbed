package Trader;

import TradeType.TradeBlock;
import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.Contract;
import client.Decimal;
import client.Types;
import historical.Request;
import utility.TradingUtility;
import utility.Utility;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import static utility.TradingUtility.getTradeDate;
import static utility.Utility.*;

public class Allstatic {
    public static final LocalDate MONDAY_OF_WEEK = getMondayOfWeek(LocalDateTime.now());
    public static volatile Map<String, Double> priceMap = new ConcurrentHashMap<>();
    public static AtomicInteger GLOBAL_REQ_ID = new AtomicInteger(30000);
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBar = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> priceMapBarDetail
            = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBarYtd = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> openMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> closeMap = new ConcurrentHashMap<>();
    public static volatile Map<Integer, Request> globalRequestMap = new ConcurrentHashMap<>();
    public volatile static Map<String, Decimal> currentPositionMap
            = new TreeMap<>(String::compareTo);
    public volatile static Map<String, Integer> openPositionMap = new HashMap<>();
    public volatile static Map<String, ConcurrentSkipListMap<LocalTime, TradeBlock>> tradesMap = new ConcurrentHashMap<>();
    public static volatile NavigableMap<Integer, OrderAugmented> globalIdOrderMap =
            new ConcurrentSkipListMap<>();
    public static volatile List<String> symbolNames = new ArrayList<>(1000);
    public static volatile LocalDate currentTradingDate = getTradeDate(LocalDateTime.now());

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
}
