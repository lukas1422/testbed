package Trader;

import api.TradingConstants;
import client.Decimal;
import client.OrderState;
import client.OrderStatus;
import controller.ApiController;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//import static Trader.BreachTrader.devOrderMap;
//import static Trader.BreachTrader.f2;
import static Trader.Tester.orderMap;
import static Trader.Tester.testOutputFile;
import static api.TradingConstants.f2;
import static api.TradingConstants.miscOutput;
import static client.OrderStatus.Filled;
import static utility.TradingUtility.outputToError;
import static utility.Utility.*;

public class PatientOrderHandler implements ApiController.IOrderHandler {

    private static Map<Integer, OrderStatus> idStatusMap = new ConcurrentHashMap<>();
    private int tradeID;
//    public static File breachMDevOutput = new File(TradingConstants.GLOBALPATH + "breachMDev.txt");

    PatientOrderHandler(int id) {
        tradeID = id;
        idStatusMap.put(id, OrderStatus.ConstructedInHandler);
    }

    int getTradeID() {
        return tradeID;
    }

    @Override
    public void orderState(OrderState orderState) {
        LocalDateTime now = LocalDateTime.now();
        if (orderMap.containsKey(tradeID)) {
            orderMap.get(tradeID).setAugmentedOrderStatus(orderState.status());
        } else {
            throw new IllegalStateException(" global id order map doesn't contain ID" + tradeID);
        }

        if (orderState.status() != idStatusMap.get(tradeID)) {
            if (orderState.status() == Filled) {
                outputToSymbolFile(orderMap.get(tradeID).getSymbol(),
                        str(orderMap.get(tradeID).getOrder().orderId(), tradeID, "*PATIENT ORDER FILL*"
                                , idStatusMap.get(tradeID) + "->" + orderState.status(),
                                now.format(f2), orderMap.get(tradeID)), testOutputFile);
                outputDetailedGen(str(orderMap.get(tradeID).getSymbol(), now.format(f2),
                        orderMap.get(tradeID)), TradingConstants.fillsOutput);
            }
            idStatusMap.put(tradeID, orderState.status());
        }
    }

    @Override
    public void orderStatus(OrderStatus status, Decimal filled, Decimal remaining, double avgFillPrice, int permId,
                            int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {

        outputDetailedGen(str(status, filled, remaining,
                avgFillPrice, permId, parentId, lastFillPrice, clientId, whyHeld, mktCapPrice), miscOutput);

    }

    @Override
    public void handle(int errorCode, String errorMsg) {
        outputToError(str("ERROR: Patient Dev Handler:", tradeID, errorCode, errorMsg
                , orderMap.get(tradeID)));
    }
}
