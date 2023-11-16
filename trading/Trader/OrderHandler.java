package Trader;

import api.TradingConstants;
import client.Decimal;
import client.OrderState;
import client.OrderStatus;
import controller.ApiController;
import enums.InventoryStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//import static Trader.BreachTrader.devOrderMap;
//import static Trader.BreachTrader.f2;
import static Trader.Tester.openOrders;
import static Trader.Tester.outputFile;
import static api.TradingConstants.f2;
import static client.OrderStatus.Filled;
import static utility.TradingUtility.outputToError;
import static utility.Utility.*;

public class OrderHandler implements ApiController.IOrderHandler {

    private static Map<Integer, OrderStatus> idStatusMap = new ConcurrentHashMap<>();
    private final int tradeID;
    //    public static File breachMDevOutput = new File(TradingConstants.GLOBALPATH + "breachMDev.txt");
    private InventoryStatus status;

    OrderHandler(int id) {
        tradeID = id;
        idStatusMap.put(id, OrderStatus.ConstructedInHandler);
    }

    OrderHandler(int id, InventoryStatus s) {
        tradeID = id;
        status = s;
        idStatusMap.put(id, OrderStatus.ConstructedInHandler);
    }

//    int getTradeID() {
//        return tradeID;
//    }

    @Override
    public void orderState(OrderState orderState) {
        LocalDateTime now = LocalDateTime.now();
        if (openOrders.containsKey(tradeID)) {
            openOrders.get(tradeID).setAugmentedOrderStatus(orderState.status());
        } else {
            throw new IllegalStateException(" global id order map doesn't contain ID" + tradeID);
        }

        if (orderState.status() != idStatusMap.get(tradeID)) {
            if (orderState.status() == Filled) {
                String symb = openOrders.get(tradeID).getSymbol();
                outputToSymbolFile(symb,
                        str(openOrders.get(tradeID).getOrder().orderId(), tradeID, "*ORDER FILL*"
                                , idStatusMap.get(tradeID) + "->" + orderState.status(),
                                now.format(f2), openOrders.get(tradeID)), outputFile);
                outputDetailedGen(str(symb, now.format(f2),
                        openOrders.get(tradeID)), TradingConstants.fillsOutput);
                if (status == InventoryStatus.BUYING_INVENTORY) {
                    Tester.inventoryStatusMap.put(symb, InventoryStatus.HAS_INVENTORY);
                } else if (status == InventoryStatus.SELLING_INVENTORY) {
                    Tester.inventoryStatusMap.put(symb, InventoryStatus.SOLD);
                }
            }
            idStatusMap.put(tradeID, orderState.status());
        }
    }

    @Override
    public void orderStatus(OrderStatus status, Decimal filled, Decimal remaining, double avgFillPrice, int permId,
                            int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {

        outputDetailedGen(str(LocalDateTime.now().format(f2), "status filled remaining avgPx", status,
                filled, remaining, avgFillPrice), outputFile);
    }

    @Override
    public void handle(int errorCode, String errorMsg) {
        outputToError(str("ERROR in order handler", tradeID, errorCode, errorMsg
                , openOrders.get(tradeID)));
    }
}
