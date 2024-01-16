package handler;

import controller.ApiController;

import java.util.List;

import static Trader.TradingUtility.*;
import static utility.Utility.pr;
import static utility.Utility.str;

public class DefaultConnectionHandler implements ApiController.IConnectionHandler {
    @Override
    public void connected() {
        pr(usTime(), "connected");
        if (getESTDateTimeNow().getMinute() == 1 && getESTDateTimeNow().getSecond() < 30) {
            outputToConnection(usDateTime(), "Conn:connected");
        }
    }

    @Override
    public void disconnected() {
        outputToConnection(usDateTime(), "Conn:disconnected");
    }

    @Override
    public void accountList(List<String> list) {
        outputToConnection(usDateTime(), "Conn: account list:", list);
    }

    @Override
    public void error(Exception e) {
        outputToConnection("Connection error", usDateTime(), e);
    }

    @Override
    public void message(int id, int errorCode, String errorMsg, String advancedORderRejectJson) {
        outputToConnection(usDateTime(),
                "Conn error ID:", id, "code:", errorCode, "msg:", errorMsg,
                advancedORderRejectJson == null ? "" : str("", advancedORderRejectJson));
    }

    @Override
    public void show(String string) {
        pr(" show string " + string);
    }
}

