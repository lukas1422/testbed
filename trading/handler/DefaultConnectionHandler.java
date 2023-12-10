package handler;

import controller.ApiController;
import Trader.TradingUtility;

import java.util.List;

import static Trader.TradingUtility.outputToGeneral;
import static Trader.TradingUtility.usTime;
import static api.TradingConstants.simpleHrMinSec;
import static utility.Utility.pr;
import static utility.Utility.str;

public class DefaultConnectionHandler implements ApiController.IConnectionHandler {
    @Override
    public void connected() {
        outputToGeneral(usTime(), "Conn:connected");
    }

    @Override
    public void disconnected() {
        outputToGeneral(usTime(), "Conn:disonnected");
    }

    @Override
    public void accountList(List<String> list) {
        outputToGeneral(usTime(), "Conn: account list:", list);
//        pr(usTime(), "account list ", list);
    }

    @Override
    public void error(Exception e) {
        outputToGeneral("Conn error:", usTime(), e);
    }

    @Override
    public void message(int id, int errorCode, String errorMsg, String advancedORderRejectJson) {
        outputToGeneral(usTime(),
                "Conn error ID:", id, "code:", errorCode, "msg:", errorMsg, advancedORderRejectJson == null ? "" :
                        str("", advancedORderRejectJson));
    }

    @Override
    public void show(String string) {
        pr(" show string " + string);
    }
}

