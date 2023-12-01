package Trader;

import auxiliary.SimpleBar;
import client.Contract;
import client.Types;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import utility.Utility;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static api.TradingConstants.*;
import static Trader.TradingUtility.*;
import static utility.Utility.*;
import static utility.Utility.histCompatibleCt;

public class Research {


    private static volatile TreeSet<String> targetStockList = new TreeSet<>();

    static volatile Map<String, Double> ytdReturn = new ConcurrentSkipListMap<>();
    static volatile Map<String, Double> dailyVolatility = new ConcurrentSkipListMap<>();

    private static ApiController apiController;
    private static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>> ytdDayData
            = new ConcurrentSkipListMap<>(String::compareTo);
    private static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);

//    static volatile ConcurrentHashMap


    Research() {
        String line;
        try (BufferedReader reader1 = new BufferedReader(new InputStreamReader(
                new FileInputStream(RELATIVEPATH + "interestListUS")))) {
            while ((line = reader1.readLine()) != null) {
                List<String> al1 = Arrays.asList(line.split("\t"));
//                fx.put(FXCurrency.get(al1.get(0)), Double.parseDouble(al1.get(1)));
                targetStockList.add(al1.get(0));
            }
        } catch (IOException x) {
            x.printStackTrace();
        }
        pr(targetStockList);

    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new Utility.DefaultLogger(), new Utility.DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);
        boolean connectionStatus = false;


        try {
            pr(" using port 4001");
            ap.connect("127.0.0.1", 4001, 6, "");
            connectionStatus = true;
            l.countDown();
//            pr(" Latch counted down 4001 " + LocalTime.now());
            pr(" Latch counted down 4001 " + LocalDateTime.now(Clock.system(ZoneId.of("America/New_York"))).format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        if (!connectionStatus) {
            pr(" using port 7496");
            ap.connect("127.0.0.1", 7496, 6, "");
            l.countDown();
            pr(" Latch counted down 7496" + LocalDateTime.now(Clock.system(ZoneId.of("America/New_York"))).format(f1));
        }

        try {
            l.await();
            pr("connected");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        pr(" Time after latch released " + LocalTime.now().format(simpleHourMinuteSec));
//        Executors.newScheduledThreadPool(10).schedule(() -> reqHoldings(ap), 500, TimeUnit.MILLISECONDS);
        targetStockList.forEach(symb -> {
            pr("requesting hist data", symb);
            Contract c = generateUSStockContract(symb);
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, ibStockReqId.addAndGet(5), histCompatibleCt(c), Research::ytdOpen,
                        365, Types.BarSize._1_day);
            });
        });
    }

    private static void ytdOpen(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);
        if (!ytdDayData.containsKey(symbol)) {
            ytdDayData.put(symbol, new ConcurrentSkipListMap<>());
        }
        LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
        ytdDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
    }

    static void compute() {
        pr("computing", LocalTime.now().format(simpleHourMinuteSec));

        targetStockList.forEach(s -> {
            double lastYearClose = ytdDayData.get(s).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
            double ytdRet = ytdDayData.get(s).lastEntry().getValue().getClose() / lastYearClose - 1;
            pr("ytdreturn ", Math.round(ytdRet * 100), "%");
            ytdReturn.put(s, 100 * ytdRet);
            pr(s, "avg vola", r(ytdDayData.get(s).entrySet().stream().mapToDouble(e -> e.getValue().getHLRange())
                    .average().orElse(0.0) * 100), "%");
            dailyVolatility.put(s, ytdDayData.get(s).entrySet().stream().mapToDouble(e -> e.getValue().getHLRange())
                    .average().orElse(0.0));
        });
        pr("daily vol",
                ytdReturn.entrySet()
                        .stream().sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()).toList());

        pr("rank by value reversed",
                dailyVolatility.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList());
        pr("rank by value",
                dailyVolatility.entrySet()
                        .stream().sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                        .map(e -> str(e.getKey(), "vol:", r(100 * e.getValue()), "%"
                                , "ytdRet:", r(ytdReturn.getOrDefault(e.getKey(), 0.0)), "%"))
                        .toList());

        pr("rank by value2",
                dailyVolatility.entrySet()
                        .stream().sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                        .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                                Collectors.mapping(e -> str("vol:", r(100 * e.getValue()), "%"
                                                , "ytdRet:", r(ytdReturn.getOrDefault(e.getKey(), 0.0)), "%")
                                        , Collectors.joining(",")))));
    }

    public static void main(String[] args) {
        Research r = new Research();
        r.connectAndReqPos();
        es.scheduleAtFixedRate(Research::compute, 10L, 10L, TimeUnit.SECONDS);
    }
}
