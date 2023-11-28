package Trader;

import api.OrderAugmented;
import client.*;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static Trader.Allstatic.*;
import static api.ControllerCalls.placeOrModifyOrderCheck;
import static api.TradingConstants.*;
import static client.Types.TimeInForce.DAY;
import static enums.AutoOrderType.*;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class ProfitTargetTrader implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {
    public static boolean TRADING_ALLOWED = true;
    private static ApiController apiController;
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();
    private static Map<String, Contract> symbolContractMap = new HashMap<>();

    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;


    Contract tencent = generateHKStockContract("700");
    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");
    Contract ul = generateUSStockContract("UL");
    Contract mcd = generateUSStockContract("MCD");
    Contract spy = generateUSStockContract("SPY");

    //avoid too many requests at once, only 50 requests allowed at one time.
    //private static Semaphore histSemaphore = new Semaphore(45);
    //Trade
    //    private static volatile Map<String, AtomicBoolean> addedMap = new ConcurrentHashMap<>();
    //    private static volatile Map<String, AtomicBoolean> liquidatedMap = new ConcurrentHashMap<>();
    //    private static volatile Map<String, AtomicBoolean> tradedMap = new ConcurrentHashMap<>();
    //    public static final LocalDateTime TODAY_MARKET_START_TIME =
    //            LocalDateTime.of(LocalDateTime.now().toLocalDate()., LocalTime.of(9, 30));

    //            LocalDateTime.of(ZonedDateTime.now().withZoneSameInstant(ZoneId.off("America/New_York")).toLocalDate(), ltof(9, 30));

    private ProfitTargetTrader() {
        pr("ProfitTarget", "HK time", LocalDateTime.now().format(f), "US Time:", getESTLocalDateTimeNow().format(f));
        pr("market start time today ", TODAY_MARKET_START_TIME);
        pr("until market start time", Duration.between(TODAY_MARKET_START_TIME, getESTLocalDateTimeNow()).toMinutes(), "minutes");

        outputToGeneral("*****START***** HK TIME:", LocalDateTime.now().format(simpleT),
                "EST:", getESTLocalDateTimeNow().format(simpleT));
        registerContract(wmt);
        registerContract(pg);
        registerContract(ul);
        registerContract(mcd);
        registerContract(spy);
        registerContract(tencent);
    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new DefaultLogger(), new DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);
        boolean connectionStatus = false;


        try {
//            pr(" using port 4001 GATEWAY");
            ap.connect("127.0.0.1", TWS_PORT, 5, "");
            connectionStatus = true;
            l.countDown();
            pr(" Latch counted down 4001 " + getESTLocalDateTimeNow().format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

//        if (!connectionStatus) {
////            pr(" using port 7496");
//            ap.connect("127.0.0.1", 7496, 5, "");
//            l.countDown();
//            pr(" Latch counted down 7496 TWS" + getESTLocalTimeNow().format(f1));
//        }

        try {
            l.await();
            pr("connected");
        } catch (InterruptedException e) {
            outputToGeneral("error in connetion:", e);
        }

        pr(" Time after latch released " + LocalTime.now().format(simpleT));
        targetStockList.forEach(symb -> {
            pr("request hist day data: target stock symb ", symb);
            Contract c = symbolContractMap.get(symb);
            if (!threeDayData.containsKey(symb)) {
                threeDayData.put(symb, new ConcurrentSkipListMap<>());
            }

            pr("requesting day data", symb);
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        histCompatibleCt(c), Allstatic::todaySoFar, 3, Types.BarSize._1_hour);
            });
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        histCompatibleCt(c), Allstatic::ytdOpen, Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day);
            });
        });

        Executors.newScheduledThreadPool(10).schedule(() -> {
            apiController.reqPositions(this);
            apiController.reqLiveOrders(this);
        }, 500, TimeUnit.MILLISECONDS);
        pr("req executions ");
        apiController.reqExecutions(new ExecutionFilter(), this);
        outputToFile("cancelling all orders on start up", outputFile);
        apiController.cancelAllOrders();
    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        symbolContractMap.put(symb, ct);
        targetStockList.add(symb);
        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
        orderStatusMap.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
        if (!ytdDayData.containsKey(symb)) {
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    static boolean noBlockingOrders(String symb) {
        pr(symb, "no blocking orders check:", orderStatusMap.get(symb));
        return orderStatusMap.get(symb).isEmpty() ||
                orderStatusMap.get(symb).values().stream().allMatch(OrderStatus::isFinished);
    }

    static double priceDividedByCost(double price, String symb) {
        if (costMap.containsKey(symb) && costMap.get(symb) != 0.0) {
            return price / costMap.get(symb);
        }
        return 1;
    }

    static void tryToTrade(Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        if (!TRADING_TIME_PRED.test(getESTLocalTimeNow())) {
            pr("not trading time");
            return;
        }
        if (!(noBlockingOrders(symb) && TRADING_ALLOWED)) {
            outputToGeneral("there are open orders ", symb, openOrders.get(symb).values(),
                    "statusMap:", orderStatusMap);
            return;
        }

        if (!ct.currency().equalsIgnoreCase("USD")) {
            pr("forbidden to buy non USD securities");
            return;
        }

        if (!threeDayPctMap.containsKey(symb) || oneDayPctMap.containsKey(symb)) {
            outputToGeneral(symb, "no percentile info");
            return;
        }

        double oneDayPercentile = oneDayPctMap.get(symb);
//        double priceOverCost = priceDividedByCost(price, symb);

        if (oneDayPercentile < 10) {
            if (symbolPosMap.get(symb).isZero()) {
                if (aggregateDelta < DELTA_LIMIT && symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE)
                        < DELTA_LIMIT_EACH_STOCK) {
                    pr("first check 3d 1d pos", symb, threeDayPctMap.get(symb),
                            oneDayPctMap.get(symb), symbolPosMap.get(symb));
                    if (threeDayPctMap.get(symb) < 40) {
                        inventoryAdder(ct, price, t, getSizeFromPrice(price));
                    }
                }
            } else if (symb.equalsIgnoreCase("SPY") && symbolPosMap.get(symb).longValue() > 0 && costMap.containsKey(symb)) {
                if (priceDividedByCost(price, symb) < 0.99) {
                    inventoryAdder(ct, price, t, Decimal.get(5));
                }
            }
        } else if (oneDayPercentile > 80 && costMap.containsKey(symb) && symbolPosMap.get(symb).longValue() > 0) {
            double priceOverCost = priceDividedByCost(price, symb);
            pr(symb, priceDividedByCost(price, symb));
            if (priceOverCost > getRequiredProfitMargin(symb)) {
                outputToGeneral("Sell", "P%:", oneDayPercentile, "priceOverCost:", priceOverCost);
                inventoryCutter(ct, price, t);
            }
        }


    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr("last", tt, symb, price, t);
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);

                if (symbolPosMap.containsKey(symb)) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }

                tryToTrade(ct, price, t);

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
//        pr("handlevol", tt, symbol, vol);
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

            pr("Updating position", symb, getESTLocalTimeNow().format(simpleT), "Position:", position.longValue(),
                    "avgCost:", avgCost);
        }
    }

    @Override
    public void positionEnd() {
        pr("position end", LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm:ss")));
        targetStockList.forEach(symb -> {
            if (!symbolPosMap.containsKey(symb)) {
                pr("symbol pos does not contain pos", symb);
                symbolPosMap.put(symb, Decimal.ZERO);
//                costMap.put(symb, Double.MAX_VALUE);
            }

            pr("SYMBOL POS COST", symb, symbolPosMap.get(symb).longValue(), costMap.getOrDefault(symb, 0.0));

            apiController.reqContractDetails(symbolContractMap.get(symb), list -> list.forEach(a -> {
//                pr("CONTRACT ID:", a.contract().symbol(), a.contract().conid());
                symbolConIDMap.put(symb, a.contract().conid());
            }));

            es.schedule(() -> {
                pr("Position end: requesting live:", symb);
//                req1ContractLive(apiController, generateUSStockContract(symb), this, false);
                req1ContractLive(apiController, symbolContractMap.get(symb), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    static void periodicCompute() {
//        pr("periodic compute", getESTLocalTimeNow().format(simpleT));
        targetStockList.forEach(symb -> {
            if (symbolPosMap.containsKey(symb)) {
                if (latestPriceMap.containsKey(symb) && costMap.containsKey(symb)) {
                    pr(symb, "price/cost-1", 100 * (latestPriceMap.get(symb) / costMap.get(symb) - 1), "%");
                }
            }
        });

        targetStockList.forEach(symb -> {
            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {
                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME));
//                pr("print stats 1d:", symb, printStats(threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME)));
//                pr("print stats 3d:", symb, printStats(threeDayData.get(symb)));

                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                pr("computeNow:", symb, getESTLocalTimeNow().format(simpleT),
                        "3d p%:", threeDayPercentile, "1d p%:", oneDayPercentile,
                        "1day data:", threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME));
            }
            if (ytdDayData.containsKey(symb) && !ytdDayData.get(symb).isEmpty()
                    && ytdDayData.get(symb).firstKey().isBefore(getYearBeginMinus1Day())) {
                double lastYearClose = ytdDayData.get(symb).floorEntry(getYearBeginMinus1Day()).getValue().getClose();
                lastYearCloseMap.put(symb, lastYearClose);
            }
        });

        aggregateDelta = targetStockList.stream().mapToDouble(s ->
                symbolPosMap.getOrDefault(s, Decimal.ZERO).
                        longValue() * latestPriceMap.getOrDefault(s, 0.0)).sum();

        targetStockList.forEach((s) ->
                symbolDeltaMap.put(s, symbolPosMap.getOrDefault(s, Decimal.ZERO).longValue() * latestPriceMap
                        .getOrDefault(s, 0.0)));

        pr("aggregate Delta", r(aggregateDelta), "each delta", symbolDeltaMap);

        openOrders.forEach((k, v) -> {
            v.forEach((k1, v1) -> {
                if (orderStatusMap.get(k).get(k1).isFinished()) {
                    outputToGeneral("removing finished orders", k, "orderID:", k1);
                    v.remove(k1);
                }
            });
        });


    }


    public static Decimal getSizeFromPrice(double price) {
        if (price < 100) {
            return Decimal.get(120);
        }
        return Decimal.get(5);
    }

    public static Decimal getAdder2Size(double price) {
        return Decimal.get(5);
    }


    private static void inventoryAdder2More(Contract ct, double price, LocalDateTime t, double perc3d, double perc1d) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symb);
//        InventoryStatus status = inventoryStatusMap.get(symb);

        if (!costMap.containsKey(symb)) {
            return;
        }

        if (openOrders.containsKey(symb) && !openOrders.get(symb).isEmpty()) {
            openOrders.get(symb).forEach((orderID, order) -> outputToGeneral("adder2 fails. Live order:", symb, "orderID:",
                    order.orderId(), "B/S", order.action(), "size:", order.totalQuantity(), "px:", order.lmtPrice()));
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT),
                    "adder2 failed, there are open orders", openOrders.get(symb));
            pr(symb, "adder2:open order");
            return;
        }

//        if (lastOrderTime.containsKey(symb) && Duration.between(lastOrderTime.get(symb), t).getSeconds() < 10) {
//            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "buying failed, has only been",
//                    Duration.between(lastOrderTime.get(symb), t).getSeconds(), "seconds");
//            pr(symb, "adding fail: need to wait longer");
//            return;
//        }

        if (price < costMap.get(symb) * 0.99) {
//            if (pos.longValue() > 0 && status != BUYING_INVENTORY) {
            Decimal sizeToBuy = getAdder2Size(price);
//                inventoryStatusMap.put(symb, BUYING_INVENTORY);
//            lastOrderTime.put(symb, t);
            int id = Allstatic.tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
            Order o = placeBidLimitTIF(bidPrice, sizeToBuy, DAY);
            orderSubmitted.get(symb).put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, id));
            outputToSymbolFile(symb, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id, o.action(),
                    "adder2:", "price:", bidPrice, "qty:", sizeToBuy, orderSubmitted.get(symb).get(id),
                    "p/b/a:", price, getDoubleFromMap(bidMap, symb), getDoubleFromMap(askMap, symb),
                    "3d perc/1d perc", perc3d, perc1d), outputFile);
//            }
        }

    }


    private static void spyAdder(double price, LocalDateTime t) {
        Decimal sizeToBuy = Decimal.get(5);

    }

    //Trade
    private static void inventoryAdder(Contract ct, double price, LocalDateTime t, Decimal sizeToBuy) {
        String symb = ibContractToSymbol(ct);

        if (symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE) + sizeToBuy.longValue() * price
                > DELTA_LIMIT_EACH_STOCK) {
            outputToGeneral(symb, getESTLocalTimeNow(), "buying exceeds limit", "current delta:",
                    symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE),
                    "proposed delta inc:", sizeToBuy.longValue() * price);
            pr(symb, "proposed buy exceeds delta limit");
            return;
        }
        int id = tradeID.incrementAndGet();
        double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
        Order o = placeBidLimitTIF(bidPrice, sizeToBuy, DAY);
        orderSubmitted.get(symb).put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
        orderStatusMap.get(symb).put(id, OrderStatus.Created);
        placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, id));
        outputToSymbolFile(symb, str("********", t.format(f1)));
        outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id, "action:", o.action(),
                "BUY:", "px:", bidPrice, "qty:", sizeToBuy, orderSubmitted.get(symb).get(id)));
    }


    private static void inventoryCutter(Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symb);

        int id = tradeID.incrementAndGet();
        double cost = costMap.getOrDefault(symb, Double.MAX_VALUE);
        double offerPrice = r(Math.max(askMap.getOrDefault(symb, price),
                costMap.getOrDefault(symb, Double.MAX_VALUE) * getRequiredProfitMargin(symb)));

        Order o = placeOfferLimitTIF(offerPrice, pos, DAY);
        orderSubmitted.get(symb).put(id, new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
        orderStatusMap.get(symb).put(id, OrderStatus.Created);
        placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, id));
        outputToSymbolFile(symb, str("********", t.format(f1)));
        outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id,
                "SELL:", "px:", offerPrice, "qty:", pos, "costBasis:", cost, orderSubmitted.get(symb).get(id)));
    }

    //request realized pnl

    //Execution details *****************
    @Override
    public void tradeReport(String tradeKey, Contract contract, Execution execution) {
        String symb = ibContractToSymbol(contract);

        tradeKeyExecutionMap.put(tradeKey, new ExecutionAugmented(execution, symb));

        pr("tradeReport:", tradeKey, symb,
                "time, side, price, shares, avgPrice:", execution.time(), execution.side(),
                execution.price(), execution.shares(), execution.avgPrice());

        outputToGeneral("tradeReport", symb,
                "time, side, price, shares, avgPrice:", execution.time(), execution.side(),
                execution.price(), execution.shares(), execution.avgPrice());
    }

    @Override
    public void tradeReportEnd() {
        pr("trade report end");
    }

    @Override
    public void commissionReport(String tradeKey, CommissionReport commissionReport) {
        String symb = tradeKeyExecutionMap.get(tradeKey).getSymbol();

        orderSubmitted.get(symb).entrySet().stream().filter(e1 -> e1.getValue().getOrder().orderId()
                        == tradeKeyExecutionMap.get(tradeKey).getExec().orderId())
                .forEach(e2 -> outputToGeneral("1.commission report", "symb:", e2.getKey(), "commission",
                        commissionReport.commission(), "realized pnl", commissionReport.realizedPNL()));

        orderSubmitted.get(symb).forEach((key1, value1) -> {
            if (value1.getOrder().orderId() == tradeKeyExecutionMap.get(tradeKey).getExec().orderId()) {
                outputToGeneral("2.commission report", "symb:", symb, "commission",
                        commissionReport.commission(), "realized pnl", commissionReport.realizedPNL());
            }
        });
    }

    //Execution end*********************************

    //Open Orders ***************************
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String symb = ibContractToSymbol(contract);
        outputToGeneral("openOrder callback:", getESTLocalTimeNow().format(f), symb,
                "order:", order, "orderState status:", orderState.status());

        orderStatusMap.get(symb).put(order.orderId(), orderState.status());

        if (orderState.status().isFinished()) {
            outputToGeneral("openOrder:removing order", order, "status:", orderState.status());
            if (openOrders.get(symb).containsKey(order.orderId())) {
                openOrders.get(symb).remove(order.orderId());
            }
        } else { //order is not finished
            openOrders.get(symb).put(order.orderId(), order);
        }
    }

    @Override
    public void openOrderEnd() {
        outputToGeneral("open order end: print all openOrders", openOrders);
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {

        outputToGeneral("openOrder orderstatus:", "orderId:", orderId, "OrderStatus:",
                status, "filled:", filled, "remaining:", remaining, "fillPrice", avgFillPrice, "lastFillPrice:", lastFillPrice
                , "clientID:", clientId, "whyHeld", whyHeld);

        if (status.isFinished()) {
            pr("openOrder/orderstatus/deleting filled from open orders", openOrders);
            openOrders.forEach((k, v) -> {
                if (v.containsKey(orderId)) {
                    outputToGeneral(k, "status:", status,
                            "removing order from openOrders. OrderID:", orderId, "order details:", v.get(orderId),
                            "remaining:", remaining);
                    v.remove(orderId);
                    outputToGeneral("remaining open orders for ", k, v);
                    outputToGeneral("remaining ALL open orders", openOrders);
                }
            });
        }
    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToGeneral("openOrder ERROR:", getESTLocalDateTimeNow().format(f), "orderId:",
                orderId, " errorCode:", errorCode, " msg:", errorMsg);
    }

    //open orders end **********************
    public static void main(String[] args) {
        ProfitTargetTrader test1 = new ProfitTargetTrader();
        test1.connectAndReqPos();
//        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCompute, 10L, 10L, TimeUnit.SECONDS);
//        es.scheduleAtFixedRate(() -> {
//            targetStockList.forEach(symb -> {
//                outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "orderStatus", orderStatusMap.get(symb));
//                outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "openOrders", openOrders.get(symb));
//            });
//        }, 10L, 60L, TimeUnit.SECONDS);
//
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            outputToGeneral("*****Ending*****", getESTLocalDateTimeNow().format(f1));
        }));
    }
}
