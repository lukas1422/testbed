package handler;

import controller.ApiController;
import Trader.TradingUtility;

import java.util.List;

import static Trader.TradingUtility.*;
import static api.TradingConstants.simpleHrMinSec;
import static utility.Utility.pr;
import static utility.Utility.str;

public class DefaultConnectionHandler implements ApiController.IConnectionHandler {
    @Override
    public void connected() {
        pr(usTime(), "connected");
        if (getESTLocalDateTimeNow().getMinute() == 1 && getESTLocalDateTimeNow().getSecond() < 30) {
            outputToGeneral(usDateTime(), "Conn:connected");
        }
    }

    @Override
    public void disconnected() {
        outputToError(usDateTime(), "Conn:disconnected");
    }

    @Override
    public void accountList(List<String> list) {
        outputToGeneral(usDateTime(), "Conn: account list:", list);
    }

    @Override
    public void error(Exception e) {
        outputToError("Connection error", usDateTime(), e);
    }

    @Override
    public void message(int id, int errorCode, String errorMsg, String advancedORderRejectJson) {
        outputToError(usDateTime(),
                "Conn error ID:", id, "code:", errorCode, "msg:", errorMsg,
                advancedORderRejectJson == null ? "" : str("", advancedORderRejectJson));
    }

    @Override
    public void show(String string) {
        pr(" show string " + string);
    }
}

