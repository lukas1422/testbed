package Trader;

import auxiliary.SimpleBar;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AllData {
    public static volatile Map<String, Double> priceMap = new ConcurrentHashMap<>();
    public static AtomicInteger GLOBAL_REQ_ID = new AtomicInteger(30000);
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBar = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, Double>> priceMapBarDetail
            = new ConcurrentHashMap<>();
    public static volatile ConcurrentHashMap<String, ConcurrentSkipListMap<LocalTime, SimpleBar>> priceMapBarYtd = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> openMap = new ConcurrentHashMap<>();
    public static volatile Map<String, Double> closeMap = new ConcurrentHashMap<>();
}
