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
import static Trader.Allstatic.orderSubmitted;
import static api.TradingConstants.f2;
import static client.OrderStatus.Filled;
import static enums.InventoryStatus.*;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class OrderHandler implements ApiController.IOrderHandler {

    public static Map<Integer, OrderStatus> tradeIDOrderStatusMap = new ConcurrentHashMap<>();
    private final int tradeID;
    //    public static File breachMDevOutput = new File(TradingConstants.GLOBALPATH + "breachMDev.txt");
    private InventoryStatus status;
    private String symbol;

    OrderHandler(int id) {
        tradeID = id;
        tradeIDOrderStatusMap.put(id, OrderStatus.ConstructedInHandler);
    }

    OrderHandler(String symb, int id, InventoryStatus s) {
        symbol = symb;
        tradeID = id;
        status = s;
        tradeIDOrderStatusMap.put(id, OrderStatus.ConstructedInHandler);
    }

    @Override
    public void orderState(OrderState orderState) {
        LocalDateTime usTimeNow = getESTLocalDateTimeNow();
        if (orderSubmitted.get(symbol).containsKey(tradeID)) {
            orderSubmitted.get(symbol).get(tradeID).setAugmentedOrderStatus(orderState.status());
        } else {
            throw new IllegalStateException(" global id order map doesn't contain ID" + tradeID);
        }

        if (orderState.status() != tradeIDOrderStatusMap.get(tradeID)) {
            if (orderState.status() == Filled) {
                outputToSymbolFile(symbol, str("orderID:", orderSubmitted.get(symbol).get(tradeID).getOrder().orderId(),
                        "tradeID", tradeID, "*ORDER FILL*", tradeIDOrderStatusMap.get(tradeID) + "->" + orderState.status(),
                        usTimeNow.format(f2), orderSubmitted.get(symbol).get(tradeID),
                        "completed status", orderState.completedStatus(), "completed time:", orderState.completedTime(),
                        "commission:", orderState.commission(), "warning:", orderState.warningText()), Allstatic.outputFile);
                outputDetailedGen(str(symbol, usTimeNow.format(f2), "completed status", orderState.completedStatus(),
                        "completed time:", orderState.completedTime(),
                        "commission:", orderState.commission(), "warning:", orderState.warningText(),
                        "status:", orderState.status(), orderSubmitted.get(symbol).get(tradeID)), TradingConstants.fillsOutput);
                if (status == InventoryStatus.BUYING_INVENTORY) {
                    Allstatic.inventoryStatusMap.put(symbol, HAS_INVENTORY);
                } else if (status == InventoryStatus.SELLING_INVENTORY) {
                    Allstatic.inventoryStatusMap.put(symbol, SOLD);
                }
            }
            tradeIDOrderStatusMap.put(tradeID, orderState.status());
        }
    }

    @Override
    public void orderStatus(OrderStatus status, Decimal filled, Decimal remaining, double avgFillPrice, int permId,
                            int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
        outputDetailedGen(str("orderhandler orderStatus:", "tradeId:", tradeID, getESTLocalDateTimeNow().format(f2),
                "status:", status, "filled:", filled, "remaining:", remaining, "avgPx:", avgFillPrice), Allstatic.outputFile);
    }

    @Override
    public void handle(int errorCode, String errorMsg) {
        outputToError(str("ERROR in order handler:", tradeID, errorCode, errorMsg, orderSubmitted.get(symbol)
                .get(tradeID)));

        outputToGeneral("ERROR in order handler:", tradeID, errorCode, errorMsg, orderSubmitted.get(symbol)
                .get(tradeID));
    }
}
