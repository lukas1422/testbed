package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import enums.InventoryStatus;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import utility.Utility;

import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static enums.InventoryStatus.BUYING_INVENTORY;
import static java.lang.Math.round;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class Tester implements LiveHandler, ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {
    private static ApiController apiController;
    private static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    static volatile AtomicInteger tradeID = new AtomicInteger(100);
//    static volatile AtomicInteger allOtherReqID = new AtomicInteger(10000);

    static volatile double aggregateDelta = 0.0;


    //    Contract gjs = generateHKStockContract("388");
//    Contract xiaomi = generateHKStockContract("1810");
    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");
    Contract brk = generateUSStockContract("BRK B");
    Contract ul = generateUSStockContract("UL");
    Contract mcd = generateUSStockContract("MCD");

    Contract spy = generateUSStockContract("SPY");

    private static Map<String, Integer> symbolConIDMap = new ConcurrentHashMap<>();

    private static final double PROFIT_LEVEL = 1.005;
    private static final double DELTA_LIMIT = 10000;
    private static final double DELTA_LIMIT_EACH_STOCK = 2000;

    static volatile NavigableMap<Integer, OrderAugmented> orderSubmitted = new ConcurrentSkipListMap<>();
    //    static volatile NavigableMap<String, List<Order>> openOrders = new ConcurrentSkipListMap<>();
    static volatile NavigableMap<String, ConcurrentHashMap<Integer, Order>> openOrders = new ConcurrentSkipListMap<>();

    public static File outputFile = new File("trading/TradingFiles/output");
    //File f = new File("trading/TradingFiles/output");

    static volatile Map<String, InventoryStatus> inventoryStatusMap = new ConcurrentHashMap<>();

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
    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>> ytdDayData = new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> threeDayData = new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> todayData = new ConcurrentSkipListMap<>(String::compareTo);


    private static volatile Map<String, Double> lastYearCloseMap = new ConcurrentHashMap<>();


    private volatile static Map<String, Double> costMap = new ConcurrentSkipListMap<>();


    private volatile static Map<String, Decimal> symbolPosMap = new ConcurrentSkipListMap<>(String::compareTo);

    private volatile static Map<String, Double> symbolDeltaMap = new ConcurrentSkipListMap<>(String::compareTo);

    private static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);

    static Map<String, LocalDateTime> lastOrderTime = new ConcurrentHashMap<>();

    //avoid too many requests at once, only 50 requests allowed at one time.
//    private static Semaphore histSemaphore = new Semaphore(45);

    //Trade
//    private static volatile Map<String, AtomicBoolean> addedMap = new ConcurrentHashMap<>();
//    private static volatile Map<String, AtomicBoolean> liquidatedMap = new ConcurrentHashMap<>();
//    private static volatile Map<String, AtomicBoolean> tradedMap = new ConcurrentHashMap<>();

//    public static final LocalDateTime TODAY_MARKET_START_TIME =
//            LocalDateTime.of(LocalDateTime.now().toLocalDate()., LocalTime.of(9, 30));

    public static final LocalDateTime TODAY_MARKET_START_TIME =
            LocalDateTime.of(getESTLocalDateTimeNow().toLocalDate(), ltof(9, 30));
//            LocalDateTime.of(ZonedDateTime.now().withZoneSameInstant(ZoneId.off("America/New_York")).toLocalDate(), ltof(9, 30));

    private Tester() {
        pr("initializing...", "HK time", LocalDateTime.now().format(f), "US Time:", getESTLocalDateTimeNow().format(f));
        pr("market start time today ", TODAY_MARKET_START_TIME);
        pr("until market start time", Duration.between(TODAY_MARKET_START_TIME, getESTLocalDateTimeNow()).toMinutes(), "minutes");

        outputToFile(str("*****START***** HK TIME:", LocalDateTime.now(), "EST:", getESTLocalDateTimeNow()), outputFile);
        registerContract(wmt);
        registerContract(pg);
//        registerContract(brk);
        registerContract(ul);
        registerContract(mcd);
        registerContract(spy);

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
            pr(" Latch counted down 4001 " + LocalDateTime.now(Clock.system(ZoneId.of("America/New_York"))).format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        if (!connectionStatus) {
            pr(" using port 7496");
            ap.connect("127.0.0.1", 7496, 5, "");
            l.countDown();
            pr(" Latch counted down 7496" + LocalDateTime.now(Clock.system(ZoneId.of("America/New_York"))).format(f1));
        }

        try {
            l.await();
            pr("connected");

        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        pr(" Time after latch released " + LocalTime.now().format(simpleT));
//        Executors.newScheduledThreadPool(10).schedule(() -> reqHoldings(ap), 500, TimeUnit.MILLISECONDS);
        targetStockList.forEach(symb -> {
            pr("request hist day data: target stock symb ", symb);
            Contract c = generateUSStockContract(symb);
            if (!threeDayData.containsKey(symb)) {
                threeDayData.put(symb, new ConcurrentSkipListMap<>());
            }

            pr("requesting day data", symb);
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, ibStockReqId.addAndGet(5), histCompatibleCt(c), Tester::todaySoFar, 3, Types.BarSize._1_min);
            });
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, ibStockReqId.addAndGet(5), histCompatibleCt(c), Tester::ytdOpen, Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day);
            });
        });

//        reqHoldings(apDev);
        Executors.newScheduledThreadPool(10).schedule(() -> {
            apiController.reqPositions(this);
            apiController.reqLiveOrders(this);
        }, 500, TimeUnit.MILLISECONDS);
//        req1ContractLive(apDev, liveCompatibleCt(wmt), this, false);
        pr("req executions ");
        apiController.reqExecutions(new ExecutionFilter(), this);
        outputToFile("cancelling all orders on start up", outputFile);
        apiController.cancelAllOrders();

//        apiController.reqPnLSingle();
//        pr("requesting contract details");
//        registerContract(wmt);
//        registerContract(pg);
//        Executors.newScheduledThreadPool(10).schedule(() -> {
////            registerContract(wmt);
////            registerContract(pg);
////            apiController.reqContractDetails(wmt,
////                    list -> list.forEach(a -> pr(a.contract().symbol(), a.contract().conid())));
//        }, 1, TimeUnit.SECONDS);
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        targetStockList.add(symb);
        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
        if (!ytdDayData.containsKey(symb)) {
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    private static void todaySoFar(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);
        LocalDateTime ld = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(date) * 1000), TimeZone.getTimeZone("America/New_York").toZoneId());

        threeDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
        pr("three day data today so far ", symbol, ld, close);
        liveData.get(symbol).put(ld, close);
    }

    // this gets YTD return
    private static void ytdOpen(Contract c, String date, double open, double high, double low, double close, long volume) {
        String symbol = ibContractToSymbol(c);

        if (!ytdDayData.containsKey(symbol)) {
            ytdDayData.put(symbol, new ConcurrentSkipListMap<>());
        }

        LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
        ytdDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr("last price", tt, symb, price, t.format(f1));
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);

                if (symbolPosMap.containsKey(symb)) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }

                //trade logic
                if (TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
                    if (threeDayPctMap.containsKey(symb) && oneDayPctMap.containsKey(symb)) {
                        if (symbolPosMap.get(symb).isZero() && inventoryStatusMap.get(symb) != BUYING_INVENTORY) {
                            if (aggregateDelta < DELTA_LIMIT && symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) < DELTA_LIMIT_EACH_STOCK) {
                                pr("first check", symb, threeDayPctMap.get(symb), oneDayPctMap.get(symb), symbolPosMap.get(symb));
                                if (threeDayPctMap.get(symb) < 40 && oneDayPctMap.get(symb) < 10 && symbolPosMap.get(symb).isZero()) {
                                    pr("second check", symb);
                                    inventoryAdder(ct, price, t, threeDayPctMap.get(symb), oneDayPctMap.get(symb));
                                }
                            }
                        }

                        if (symbolPosMap.get(symb).longValue() > 0) {
                            if (costMap.containsKey(symb)) {
                                pr(symb, "price/cost", price / costMap.getOrDefault(symb, Double.MAX_VALUE));
                                if (price / costMap.getOrDefault(symb, Double.MAX_VALUE) > getRequiredProfitMargin(symb)) {
                                    inventoryCutter(ct, price, t);
                                }
                            }
                        }
                    }
                }
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
        String symb = ibContractToSymbol(contract);

        if (!contract.symbol().equals("USD") && targetStockList.contains(symb)) {
            symbolPosMap.put(symb, position);
            costMap.put(symb, avgCost);
            if (position.longValue() > 0) {
                inventoryStatusMap.put(symb, InventoryStatus.HAS_INVENTORY);
            } else if (position.isZero() && inventoryStatusMap.get(symb) != BUYING_INVENTORY) {
                inventoryStatusMap.put(symb, InventoryStatus.NO_INVENTORY);
            } else {
                inventoryStatusMap.put(symb, InventoryStatus.UNKNOWN);
            }
            pr("Updating position", symb, getESTLocalTimeNow().format(simpleT), "Position:", position.longValue(),
                    "avgCost:", avgCost, "inventoryStatus", inventoryStatusMap.get(symb));
        }
    }

    @Override
    public void positionEnd() {
        pr("position end", LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm:ss")));
        targetStockList.forEach(symb -> {
            if (!symbolPosMap.containsKey(symb)) {
                pr("symbol pos does not contain pos", symb);
                symbolPosMap.put(symb, Decimal.ZERO);
                inventoryStatusMap.put(symb, InventoryStatus.NO_INVENTORY);
                costMap.put(symb, 0.0);
            }

            pr("SYMBOL POS INVENTORY", symb, symbolPosMap.get(symb).longValue(), inventoryStatusMap.get(symb), costMap.get(symb));

            apiController.reqContractDetails(generateUSStockContract(symb), list -> list.forEach(a -> {
                pr("CONTRACT ID:", a.contract().symbol(), a.contract().conid());
                symbolConIDMap.put(symb, a.contract().conid());
            }));

            es.schedule(() -> {
                pr("Position end: requesting live for fut:", symb);
                req1ContractLive(apiController, liveCompatibleCt(generateUSStockContract(symb)), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    static void periodicCompute() {
        pr("periodic compute", getESTLocalTimeNow().format(simpleT));
        targetStockList.forEach(symb -> {
            if (symbolPosMap.containsKey(symb)) {
                if (symbolPosMap.get(symb).isZero()) {
                    inventoryStatusMap.put(symb, InventoryStatus.NO_INVENTORY);
                } else if (latestPriceMap.containsKey(symb) && costMap.containsKey(symb)) {
                    pr(symb, "price/cost", Math.round(100 * (latestPriceMap.get(symb) / costMap.get(symb) - 1)), "%");
                }
            }
        });

//        pr("calculate percentile", getESTLocalTimeNow().format(f1), "target list size", targetStockList.size());

        targetStockList.forEach(symb -> {
            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {

                ConcurrentSkipListMap<LocalDateTime, SimpleBar> threeDayMap = threeDayData.get(symb);

                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME));

                if (symb.equalsIgnoreCase("SPY")) {

                    pr("threeday data", threeDayData.get(symb));
                    pr("oneday data", threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME));
                    pr("high time ", threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME).entrySet().stream()
                            .max(Comparator.comparingDouble(e -> e.getValue().getHigh())).get());
                    pr("low time ", threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME).entrySet().stream()
                            .min(Comparator.comparingDouble(e -> e.getValue().getLow())).get());
                }

                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                pr("compute", symb, getESTLocalTimeNow().format(simpleT),
                        "3d p%:", round(threeDayPercentile), "1d p%:", round(oneDayPercentile));
            }
            pr("compute after percentile map", symb);
            if (ytdDayData.containsKey(symb) && !ytdDayData.get(symb).isEmpty() && ytdDayData.get(symb).firstKey().isBefore(getYearBeginMinus1Day())) {
                pr("ytd size ", symb, ytdDayData.get(symb).size(), "first key", ytdDayData.get(symb).firstKey(), getYearBeginMinus1Day());
                double lastYearClose = ytdDayData.get(symb).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
//                double returnOnYear = ytdDayData.get(symb).lastEntry().getValue().getClose() / lastYearClose - 1;
                lastYearCloseMap.put(symb, lastYearClose);
//                pr("last year close", lastYearClose);
//                pr("ytd return", symb, round(returnOnYear * 100), "%");
            }
        });
        pr("before agg delta ");

        aggregateDelta = targetStockList.stream().mapToDouble(s ->
                symbolPosMap.getOrDefault(s, Decimal.ZERO).
                        longValue() * latestPriceMap.getOrDefault(s, 0.0)).sum();

        targetStockList.forEach((s) ->
                symbolDeltaMap.put(s, symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue() * latestPriceMap
                        .getOrDefault(s, 0.0)));

        pr("aggregate Delta", aggregateDelta, "each delta", symbolDeltaMap);

        if (!openOrders.isEmpty()) {
            openOrders.forEach((k, v) -> {
                if (v.isEmpty()) {
                    openOrders.remove(k);
                }
            });
            outputToGeneral(str("openOrderMap is not empty", openOrders));
        } else {
            pr("there  is no open orders");
        }

    }


    public static Decimal getTradeSizeFromPrice(double price) {
        if (price < 100) {
            return Decimal.get(20);
        }
        return Decimal.get(10);
    }

    public static Decimal getAdder2Size(double price) {
        return Decimal.get(5);
    }


    private static void inventoryAdder2More(Contract ct, double price, LocalDateTime t, double perc3d, double perc1d) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symb);
        InventoryStatus status = inventoryStatusMap.get(symb);

        if (!costMap.containsKey(symb)) {
            return;
        }

        if (openOrders.containsKey(symb) && !openOrders.get(symb).isEmpty()) {
            openOrders.get(symb).forEach((oID, ord) -> outputToGeneral("adder2 fails. Live order:", symb, "orderID:",
                    ord.orderId(), "B/S", ord.action(), "size:", ord.totalQuantity(), "px:", ord.lmtPrice()));
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT),
                    "adder2 failed, there are open orders", openOrders.get(symb));
            pr(symb, "adder2:open order");
            return;
        }

        if (lastOrderTime.containsKey(symb) && Duration.between(lastOrderTime.get(symb), t).getSeconds() < 10) {
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "buying failed, has only been",
                    Duration.between(lastOrderTime.get(symb), t).getSeconds(), "seconds");
            pr(symb, "adding fail: need to wait longer");
            return;
        }

        if (price < costMap.get(symb) * 0.99) {
            if (pos.longValue() > 0 && status != BUYING_INVENTORY) {
                Decimal sizeToBuy = getAdder2Size(price);
                inventoryStatusMap.put(symb, BUYING_INVENTORY);
                lastOrderTime.put(symb, t);
                int id = tradeID.incrementAndGet();
                double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
                Order o = placeBidLimitTIF(bidPrice, sizeToBuy, DAY);
                orderSubmitted.put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
                placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, BUYING_INVENTORY));
                outputToSymbolFile(symb, str("********", t.format(f1)), outputFile);
                outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id, o.action(),
                        "adder2:", "price:", bidPrice, "qty:", sizeToBuy, orderSubmitted.get(id),
                        "p/b/a", price, getDoubleFromMap(bidMap, symb), getDoubleFromMap(askMap, symb),
                        "3d perc/1d perc", perc3d, perc1d), outputFile);
            }
        }

    }

    //Trade
    private static void inventoryAdder(Contract ct, double price, LocalDateTime t, double perc3d, double perc1d) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symb);
        InventoryStatus status = inventoryStatusMap.get(symb);

        if (status == BUYING_INVENTORY) {
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "already buying, exiting");
            return;
        }

        if (openOrders.containsKey(symb) && !openOrders.get(symb).isEmpty()) {
            openOrders.get(symb).forEach((oID, ord) -> outputToGeneral("adder fails. Live order:", symb, "orderID:",
                    ord.orderId(), "B/S", ord.action(), "size:", ord.totalQuantity(), "px:", ord.lmtPrice()));
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT),
                    "buying failed, there are open orders", openOrders.get(symb));
            pr(symb, "adding fail:open order");
            return;
        }

        if (lastOrderTime.containsKey(symb) && Duration.between(lastOrderTime.get(symb), t).getSeconds() < 10) {
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "buying failed, has only been",
                    Duration.between(lastOrderTime.get(symb), t).getSeconds(), "seconds");
            pr(symb, "adding fail: need to wait longer");
            return;
        }

        Decimal sizeToBuy = getTradeSizeFromPrice(price);

        if (symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) + sizeToBuy.longValue() * price
                > DELTA_LIMIT_EACH_STOCK) {
            outputToGeneral(symb, "after buying exceeds delta limit", "current delta:",
                    symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE),
                    "proposed delta inc:", sizeToBuy.longValue() * price);
            pr(symb, "proposed buy exceeds delta limit");
            return;
        }

        if (pos.isZero() && status == InventoryStatus.NO_INVENTORY) {
            inventoryStatusMap.put(symb, BUYING_INVENTORY);
            lastOrderTime.put(symb, t);
            int id = tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
            Order o = placeBidLimitTIF(bidPrice, sizeToBuy, DAY);
            orderSubmitted.put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, BUYING_INVENTORY));
            outputToSymbolFile(symb, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id, o.action(),
                    "BUY INVENTORY:", "price:", bidPrice, "qty:", sizeToBuy, orderSubmitted.get(id),
                    "p/b/a", price, getDoubleFromMap(bidMap, symb), getDoubleFromMap(askMap, symb),
                    "3d perc/1d perc", perc3d, perc1d), outputFile);
        }
    }


    private static void inventoryCutter(Contract ct, double price, LocalDateTime t) {
        String symbol = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symbol);

        if (inventoryStatusMap.get(symbol) == InventoryStatus.SELLING_INVENTORY) {
            outputToGeneral(str("CUTTER FAIL. selling, cannot sell again", LocalDateTime.now(), symbol));
            return;
        }

        if (openOrders.containsKey(symbol) && !openOrders.get(symbol).isEmpty()) {
            if (openOrders.get(symbol).entrySet().stream().anyMatch(e -> e.getValue().action() == Types.Action.SELL)) {
                pr("CUTTER FAIL. There is a live selling order", openOrders.get(symbol).entrySet()
                        .stream().filter(e -> e.getValue().action() == Types.Action.SELL).findFirst().get());
                return;
            }
        }

        if (lastOrderTime.containsKey(symbol) && Duration.between(lastOrderTime.get(symbol), t).getSeconds() < 10) {
            outputToGeneral(symbol, getESTLocalTimeNow().format(simpleT), "CUTTER FAIL, wait 10 seconds");
            return;
        }

        if (pos.longValue() > 0) {
            lastOrderTime.put(symbol, t);
            inventoryStatusMap.put(symbol, InventoryStatus.SELLING_INVENTORY);
            int id = tradeID.incrementAndGet();
            double cost = costMap.getOrDefault(symbol, Double.MAX_VALUE);
            double offerPrice = r(Math.max(askMap.getOrDefault(symbol, price),
                    costMap.getOrDefault(symbol, Double.MAX_VALUE) * getRequiredProfitMargin(symbol)));

            Order o = placeOfferLimitTIF(offerPrice, pos, DAY);
            orderSubmitted.put(id, new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(id, InventoryStatus.SELLING_INVENTORY));
            outputToSymbolFile(symbol, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symbol, str("orderID:", o.orderId(), "tradeID", id,
                    "SELL INVENTORY:", "offer price:", offerPrice, "cost:", cost,
                    Optional.ofNullable(orderSubmitted.get(id)).orElse(new OrderAugmented()),
                    "price/bid/ask:", price, getDoubleFromMap(bidMap, symbol), getDoubleFromMap(askMap, symbol)), outputFile);
        }
    }


    //request realized pnl

    /**
     * Execution details
     */
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        pr("tradeReport", tradeKey, ibContractToSymbol(contract), execution);

        String symb = ibContractToSymbol(contract);
        tradeKeyExecutionMap.put(tradeKey, execution);

        pr("tradeReport:", tradeKey, symb,
                "time, side, price, shares, avgPrice:", execution.time(), execution.side(),
                execution.price(), execution.shares(), execution.avgPrice(),
                Optional.ofNullable(orderSubmitted.get(execution.orderId())).map(OrderAugmented::getSymbol).orElse(""));

        outputToFile(str("tradeReport", symb,
                "time, side, price, shares, avgPrice:", execution.time(), execution.side(),
                execution.price(), execution.shares(), execution.avgPrice()), outputFile);
    }

    @Override
    public void tradeReportEnd() {
        pr("trade report end");
    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {

        String symb = Optional.ofNullable(tradeKeyExecutionMap.get(tradeKey))
                .map(exec -> orderSubmitted.get(exec.orderId())).map(OrderAugmented::getSymbol).orElse("");

        outputToGeneral("commission report", "symb:", symb, "commission",
                commissionReport.commission(), "realized pnl", commissionReport.realizedPNL());
    }


    //Open Orders
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String symb = ibContractToSymbol(contract);
        outputToFile(str("open order:", getESTLocalDateTimeNow().format(f), symb,
                "order", order, "orderstate:", orderState), outputFile);

        if (!openOrders.containsKey(symb)) {
            openOrders.put(symb, new ConcurrentHashMap<>());
        }

        openOrders.get(symb).put(order.orderId(), order);

        outputToGeneral("open order", getESTLocalDateTimeNow().format(f), symb,
                "orderID", order.orderId(), "orderType:", order.orderType(), "action:", order.action(),
                "quantity", order.totalQuantity(), "orderPrice", order.lmtPrice(), "orderstate", orderState);
    }

    @Override
    public void openOrderEnd() {
        pr("Open order end. Print all open orders:", openOrders);
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {
        outputToFile(str("openorder orderstatus:", "orderId", orderId, "OrderStatus",
                status, "filled", filled, "remaining", remaining), outputFile);
        if (status == OrderStatus.Filled && remaining.isZero()) {
            pr("in orderstatus deleting filled from liveorders");
            openOrders.forEach((k, v) -> {
                if (v.containsKey(orderId)) {
                    v.remove(orderId);
                    outputToGeneral(k, "removing order from ordermap.OrderID:", orderId, "order details:", v.get(orderId));
                    outputToGeneral("remaining open orders for ", k, v);
                    outputToGeneral("remaining ALL open orders", openOrders);
                }
            });
        }
    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToFile(str("HANDLE ORDER:", getESTLocalDateTimeNow().format(f), "order id",
                orderId, "errorCode", errorCode, "msg:", errorMsg), outputFile);
    }

    //open orders end
    public static void main(String[] args) {
        Tester test1 = new Tester();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(Tester::periodicCompute, 10L, 10L, TimeUnit.SECONDS);
//        es.scheduleAtFixedRate(Tester::reqHoldings, 10L, 10L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pr("closing hook ");
            outputToFile(str("*****Ending*****", getESTLocalDateTimeNow().format(f1)), outputFile);
            orderSubmitted.forEach((k, v) -> {
                if (v.getAugmentedOrderStatus() != OrderStatus.Filled && v.getAugmentedOrderStatus() != OrderStatus.PendingCancel) {
                    outputToFile(str("unexecuted orders:", v.getSymbol(),
                            "Shutdown status", getESTLocalTimeNow().format(f1), v.getAugmentedOrderStatus(), v), outputFile);
                }
            });
            apiController.cancelAllOrders();
        }));
    }

}
