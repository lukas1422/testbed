package Trader;

import api.OrderAugmented;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import enums.InventoryStatus;
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
import static enums.InventoryStatus.*;
import static java.lang.Math.round;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class ProfitTargetTrader implements LiveHandler,
        ApiController.IPositionHandler, ApiController.ITradeReportHandler, ApiController.ILiveOrderHandler {
    private static ApiController apiController;
    //    static volatile AtomicInteger allOtherReqID = new AtomicInteger(10000);

    //    Contract gjs = generateHKStockContract("388");
//    Contract xiaomi = generateHKStockContract("1810");
    private static volatile TreeSet<String> targetStockList = new TreeSet<>();

    Contract wmt = generateUSStockContract("WMT");
    Contract pg = generateUSStockContract("PG");
    //    Contract brk = generateUSStockContract("BRK B");
    Contract ul = generateUSStockContract("UL");
    Contract mcd = generateUSStockContract("MCD");


    //    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, SimpleBar>> todayData
//    = new ConcurrentSkipListMap<>(String::compareTo);

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

        outputToFile(str("*****START***** HK TIME:", LocalDateTime.now(), "EST:", getESTLocalDateTimeNow()), outputFile);
        registerContract(wmt);
        registerContract(pg);
//        registerContract(brk);
        registerContract(ul);
        registerContract(mcd);
//        registerContract(spy);
    }


    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new DefaultLogger(), new DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);
        boolean connectionStatus = false;

        try {
            pr(" using port 4001 GATEWAY");
            ap.connect("127.0.0.1", 4001, 5, "");
            connectionStatus = true;
            l.countDown();
            pr(" Latch counted down 4001 " + getESTLocalDateTimeNow().format(f1));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        if (!connectionStatus) {
            pr(" using port 7496");
            ap.connect("127.0.0.1", 7496, 5, "");
            l.countDown();
            pr(" Latch counted down 7496 TWS" + getESTLocalTimeNow().format(f1));
        }

        try {
            l.await();
            pr("connected");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        pr(" Time after latch released " + LocalTime.now().format(simpleT));
        targetStockList.forEach(symb -> {
            pr("request hist day data: target stock symb ", symb);
            Contract c = generateUSStockContract(symb);
            if (!threeDayData.containsKey(symb)) {
                threeDayData.put(symb, new ConcurrentSkipListMap<>());
            }

            pr("requesting day data", symb);
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        histCompatibleCt(c), Allstatic::todaySoFar, 3, Types.BarSize._1_min);
            });
            CompletableFuture.runAsync(() -> {
                reqHistDayData(apiController, Allstatic.ibStockReqId.addAndGet(5),
                        histCompatibleCt(c), Allstatic::ytdOpen, Math.min(364, getCalendarYtdDays() + 10), Types.BarSize._1_day);
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

    }

    private static void registerContract(Contract ct) {
        String symb = ibContractToSymbol(ct);
        targetStockList.add(symb);
        orderSubmitted.put(symb, new ConcurrentSkipListMap<>());
        openOrders.put(symb, new ConcurrentHashMap<>());
        if (!liveData.containsKey(symb)) {
            liveData.put(symb, new ConcurrentSkipListMap<>());
        }
        if (!ytdDayData.containsKey(symb)) {
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
        }
    }

    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);
        pr("last", tt, symb, price, t);

        switch (tt) {
            case LAST:
//                pr("last price", tt, symb, price, t.format(f1));
                latestPriceMap.put(symb, price);
                liveData.get(symb).put(t, price);

                if (symbolPosMap.containsKey(symb)) {
                    symbolDeltaMap.put(symb, price * symbolPosMap.get(symb).longValue());
                }

                //trade logic
                if (orderSubmitted.get(symb).values().stream().anyMatch(e ->
                        e.getAugmentedOrderStatus() != OrderStatus.Filled)) {

                    pr("All unfilled orders in orderSubmitted:", symb, orderSubmitted.get(symb).values().stream()
                            .filter(e -> e.getAugmentedOrderStatus() != OrderStatus.Filled)
                            .toList());
                }
//                if (openOrders.containsKey(symb) && !openOrders.get(symb).isEmpty()) {
//                    pr("open orders:", symb, openOrders.get(symb));
//                }

                if (TRADING_TIME_PRED.test(getESTLocalTimeNow())) {

                    if (openOrders.get(symb).isEmpty()) {
                        if (threeDayPctMap.containsKey(symb) && oneDayPctMap.containsKey(symb)) {
                            if (symbolPosMap.get(symb).isZero() && inventoryStatusMap.get(symb) != BUYING_INVENTORY) {
                                if (aggregateDelta < DELTA_LIMIT && symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE)
                                        < DELTA_LIMIT_EACH_STOCK) {
                                    pr("first check 3d 1d pos", symb, threeDayPctMap.get(symb), oneDayPctMap.get(symb), symbolPosMap.get(symb));
                                    if (threeDayPctMap.get(symb) < 40 && oneDayPctMap.get(symb) < 10) {
                                        pr("second check", symb);
                                        inventoryAdder(ct, price, t, threeDayPctMap.get(symb), oneDayPctMap.get(symb));
                                    }
                                }
                            }

                            if (symbolPosMap.get(symb).longValue() > 0) {
                                if (costMap.containsKey(symb) && costMap.get(symb) != 0.0) {
                                    pr(symb, "price/cost", price / costMap.getOrDefault(symb, Double.MAX_VALUE));
                                    if (price / costMap.getOrDefault(symb, Double.MAX_VALUE) > getRequiredProfitMargin(symb)) {
                                        inventoryCutter(ct, price, t);
                                    }
//                                else if (price / costMap.getOrDefault(symb, Double.MAX_VALUE) < 0.99) {
//                                    inventoryAdder2More(ct, price, t, threeDayPctMap.get(symb), oneDayPctMap.get(symb));
//                                }
                                }
                            }
                        }
                    } else {
                        pr("there are open orders ", symb, openOrders.get(symb).values());
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

            if (position.isZero()) {
                if (!inventoryStatusMap.containsKey(symb)) {
                    inventoryStatusMap.put(symb, NO_INVENTORY);
                } else if (inventoryStatusMap.get(symb) != BUYING_INVENTORY) {
                    inventoryStatusMap.put(symb, NO_INVENTORY);
                } else if (inventoryStatusMap.get(symb) == SOLD) {
                    inventoryStatusMap.put(symb, NO_INVENTORY);
                }
            } else if (position.longValue() > 0) {
                inventoryStatusMap.put(symb, InventoryStatus.HAS_INVENTORY);
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
                costMap.put(symb, 0.0);
            }

            if (!inventoryStatusMap.containsKey(symb)) {
                inventoryStatusMap.put(symb, NO_INVENTORY);
            }

            pr("SYMBOL POS INVENTORY", symb, symbolPosMap.get(symb).longValue(), inventoryStatusMap.get(symb), costMap.get(symb));

            apiController.reqContractDetails(generateUSStockContract(symb), list -> list.forEach(a -> {
                pr("CONTRACT ID:", a.contract().symbol(), a.contract().conid());
                symbolConIDMap.put(symb, a.contract().conid());
            }));

            es.schedule(() -> {
                pr("Position end: requesting live:", symb);
//                req1ContractLive(apiController, liveCompatibleCt(generateUSStockContract(symb)), this, false);
                req1ContractLive(apiController, generateUSStockContract(symb), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    static void periodicCompute() {
        pr("periodic compute", getESTLocalTimeNow().format(simpleT));
        targetStockList.forEach(symb -> {
            if (symbolPosMap.containsKey(symb)) {
                //if sold, can be reset here.
                if (symbolPosMap.get(symb).isZero() && inventoryStatusMap.get(symb) != BUYING_INVENTORY) {
                    inventoryStatusMap.put(symb, InventoryStatus.NO_INVENTORY);
                } else if (latestPriceMap.containsKey(symb) && costMap.containsKey(symb)) {
                    pr(symb, "price/cost-1", 100 * (latestPriceMap.get(symb) / costMap.get(symb) - 1), "%");
                }
            }
        });

        targetStockList.forEach(symb -> {
            if (threeDayData.containsKey(symb) && !threeDayData.get(symb).isEmpty()) {

                ConcurrentSkipListMap<LocalDateTime, SimpleBar> threeDayMap = threeDayData.get(symb);

                double threeDayPercentile = calculatePercentileFromMap(threeDayData.get(symb));
                double oneDayPercentile = calculatePercentileFromMap(threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME));
                pr("print stats 1d:", symb, printStats(threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME)));
                pr("print stats 3d:", symb, printStats(threeDayData.get(symb)));

                threeDayPctMap.put(symb, threeDayPercentile);
                oneDayPctMap.put(symb, oneDayPercentile);
                pr("computeNow:", symb, getESTLocalTimeNow().format(simpleT),
                        "3d p%:", threeDayPercentile, "1d p%:", oneDayPercentile,
                        "1day data:", threeDayData.get(symb).tailMap(TODAY_MARKET_START_TIME));
            }
//            pr("compute after percentile map", symb);
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
            openOrders.get(symb).forEach((orderID, order) -> outputToGeneral("adder2 fails. Live order:", symb, "orderID:",
                    order.orderId(), "B/S", order.action(), "size:", order.totalQuantity(), "px:", order.lmtPrice()));
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
                int id = Allstatic.tradeID.incrementAndGet();
                double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
                Order o = placeBidLimitTIF(bidPrice, sizeToBuy, DAY);
                orderSubmitted.get(symb).put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
                placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, id, BUYING_INVENTORY));
                outputToSymbolFile(symb, str("********", t.format(f1)), outputFile);
                outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id, o.action(),
                        "adder2:", "price:", bidPrice, "qty:", sizeToBuy, orderSubmitted.get(symb).get(id),
                        "p/b/a:", price, getDoubleFromMap(bidMap, symb), getDoubleFromMap(askMap, symb),
                        "3d perc/1d perc", perc3d, perc1d), outputFile);
            }
        }

    }

//    public boolean checkTradable(String symb) {
//        if (!openOrders.containsKey(symb) && !orderSubmitted.containsKey(symb)) {
//            return true;
//        }
//        return false;
//    }

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
            pr(symb, "adding fail:open order", openOrders.get(symb));
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
                > Allstatic.DELTA_LIMIT_EACH_STOCK) {
            outputToGeneral(symb, "after buying exceeds delta limit", "current delta:",
                    symbolDeltaMap.getOrDefault(symb, Double.MAX_VALUE),
                    "proposed delta inc:", sizeToBuy.longValue() * price);
            pr(symb, "proposed buy exceeds delta limit");
            return;
        }

        if (pos.isZero() && status == NO_INVENTORY) {
            inventoryStatusMap.put(symb, BUYING_INVENTORY);
            lastOrderTime.put(symb, t);
            int id = tradeID.incrementAndGet();
            double bidPrice = r(Math.min(price, bidMap.getOrDefault(symb, price)));
            Order o = placeBidLimitTIF(bidPrice, sizeToBuy, DAY);
            orderSubmitted.get(symb).put(id, new OrderAugmented(ct, t, o, INVENTORY_ADDER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, id, BUYING_INVENTORY));
            outputToSymbolFile(symb, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id, o.action(),
                    "BUY:", "price:", bidPrice, "qty:", sizeToBuy, orderSubmitted.get(symb).get(id),
                    "p/b/a", price, getDoubleFromMap(bidMap, symb), getDoubleFromMap(askMap, symb),
                    "3d perc/1d perc", perc3d, perc1d), outputFile);
        }
    }


    private static void inventoryCutter(Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);
        Decimal pos = symbolPosMap.get(symb);

        if (inventoryStatusMap.get(symb) == SELLING_INVENTORY) {
            outputToGeneral("CUTTER FAIL. selling, cannot sell again", LocalDateTime.now(), symb);
            return;
        }

        if (openOrders.containsKey(symb) && !openOrders.get(symb).isEmpty()) {
            outputToGeneral("cutter failed, there is live order.", openOrders.get(symb));
            return;
        }

        if (lastOrderTime.containsKey(symb) && Duration.between(lastOrderTime.get(symb), t).getSeconds() < 10) {
            outputToGeneral(symb, getESTLocalTimeNow().format(simpleT), "CUTTER FAIL, wait 10 seconds");
            return;
        }

        if (pos.longValue() > 0) {
            lastOrderTime.put(symb, t);
            inventoryStatusMap.put(symb, SELLING_INVENTORY);
            int id = Allstatic.tradeID.incrementAndGet();
            double cost = costMap.getOrDefault(symb, Double.MAX_VALUE);
            double offerPrice = r(Math.max(askMap.getOrDefault(symb, price),
                    costMap.getOrDefault(symb, Double.MAX_VALUE) * getRequiredProfitMargin(symb)));

            Order o = placeOfferLimitTIF(offerPrice, pos, DAY);
            orderSubmitted.get(symb).put(id, new OrderAugmented(ct, t, o, INVENTORY_CUTTER));
            placeOrModifyOrderCheck(apiController, ct, o, new OrderHandler(symb, id, SELLING_INVENTORY));
            outputToSymbolFile(symb, str("********", t.format(f1)), outputFile);
            outputToSymbolFile(symb, str("orderID:", o.orderId(), "tradeID:", id,
                    "SELL:", "offer price:", offerPrice, "cost:", cost,
                    orderSubmitted.get(symb).get(id),
                    "price/bid/ask:", price, getDoubleFromMap(bidMap, symb), getDoubleFromMap(askMap, symb)), outputFile)
            ;
        }
    }


    //request realized pnl

    /**
     * Execution details
     */
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


    //Open Orders
    @Override
    public void openOrder(Contract contract, Order order, OrderState orderState) {
        String symb = ibContractToSymbol(contract);
        outputToGeneral("open order:", getESTLocalDateTimeNow().format(f), symb,
                "order:", order, "orderState:", orderState);

        openOrders.get(symb).put(order.orderId(), order);

        outputToGeneral("open order", getESTLocalDateTimeNow().format(f), symb,
                "orderID", order.orderId(), "orderType:", order.orderType(), "action:", order.action(),
                "quantity", order.totalQuantity(), "orderPrice", order.lmtPrice(), "orderState", orderState);
    }

    @Override
    public void openOrderEnd() {
        pr("Open order end. Print all open orders:", openOrders);
    }

    void findAndRemoveOrder(NavigableMap<String, ConcurrentHashMap<Integer, Order>> m, int orderID) {
        m.forEach((k, v) -> v.forEach((k1, v1) -> {
            if (v1.orderId() == orderID) {
                m.get(k).remove(k1);
            }
        }));
    }

    @Override
    public void orderStatus(int orderId, OrderStatus status, Decimal filled, Decimal remaining,
                            double avgFillPrice, int permId, int parentId, double lastFillPrice,
                            int clientId, String whyHeld, double mktCapPrice) {
        outputToGeneral("openOrder orderstatus:", "orderId:", orderId, "OrderStatus:",
                status, "filled:", filled, "remaining:", remaining);
        if (status == OrderStatus.Filled && remaining.isZero()) {
            pr("in profit target/orderstatus/deleting filled from open orders", openOrders);

//            targetStockList.forEach(s -> {
//                if (openOrders.containsKey(s) && !openOrders.get(s).isEmpty()) {
//                    if (openOrders.get(s).containsKey(orderId)) {
//                        outputToGeneral(s, "removing order from ordermap. OrderID:", orderId, "order details:",
//                                openOrders.get(s).get(orderId));
//                        openOrders.get(s).remove(orderId);
//                        outputToGeneral("remaining open orders for ", s, openOrders.get(s));
//                        outputToGeneral("remaining ALL open orders", openOrders);
//                    }
//                }
//            });

            //set invetorystatus here?

            openOrders.forEach((k, v) -> {
                if (v.containsKey(orderId)) {
                    outputToGeneral(k, "removing order from openOrders. OrderID:", orderId, "order details:", v.get(orderId));
                    v.remove(orderId);
                    outputToGeneral("remaining open orders for ", k, v);
                    outputToGeneral("remaining ALL open orders", openOrders);
                }
            });
        }
    }

    @Override
    public void handle(int orderId, int errorCode, String errorMsg) {
        outputToGeneral("HANDLE ORDER ERROR:", getESTLocalDateTimeNow().format(f), "orderId:",
                orderId, " errorCode:", errorCode, " msg:", errorMsg);
    }

    //open orders end
    public static void main(String[] args) {
        ProfitTargetTrader test1 = new ProfitTargetTrader();
        test1.connectAndReqPos();
        es.scheduleAtFixedRate(ProfitTargetTrader::periodicCompute, 10L, 10L, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            outputToGeneral("*****Ending*****", getESTLocalDateTimeNow().format(f1));
            orderSubmitted.forEach((k, v) -> {
                v.forEach((k1, v1) -> {
                    if (v1.getAugmentedOrderStatus() != OrderStatus.Filled &&
                            v1.getAugmentedOrderStatus() != OrderStatus.PendingCancel) {
                        outputToFile(str("unexecuted orders on closing:", v1.getSymbol(),
                                "Shutdown status", getESTLocalTimeNow().format(f1),
                                v1.getAugmentedOrderStatus(), v), outputFile);
                    }
                });
            });
        }));
    }
}
