package Trader;

import controller.ApiController;

import static utility.Utility.pr;

public class BasicOrderCancelHandler implements ApiController.IOrderCancelHandler {

    //private static Map<Integer, OrderStatus> idStatusMap = new ConcurrentHashMap<>();
    private int orderID;

    BasicOrderCancelHandler(int id) {
        orderID = id;
    }


    @Override
    public void orderStatus(String orderStatus) {
        pr(orderStatus);
    }

    @Override
    public void handle(int errorCode, String errorMsg) {
        pr("error code",errorCode,"errorMsg",errorMsg);
    }
}
