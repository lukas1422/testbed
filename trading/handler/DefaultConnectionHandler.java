package handler;

import controller.ApiController;
import Trader.TradingUtility;

import java.util.List;

import static Trader.TradingUtility.outputToGeneral;
import static Trader.TradingUtility.usTime;
import static api.TradingConstants.simpleHrMinSec;
import static utility.Utility.pr;

public class DefaultConnectionHandler implements ApiController.IConnectionHandler {
    @Override
    public void connected() {
        outputToGeneral(usTime(), "connection handler: connected");
        pr(usTime(), "Default Conn Handler: connected");
    }

    @Override
    public void disconnected() {
        outputToGeneral(usTime(), "connection handler: disonnected");
        pr(usTime(), "Default Conn Handler: disconnected");
    }

    @Override
    public void accountList(List<String> list) {
        outputToGeneral(usTime(), "connection handler: account list:", list);
        pr(usTime(), "account list ", list);
    }

    @Override
    public void error(Exception e) {
        outputToGeneral(" error in connection handler:", usTime(), e);
    }

    @Override
    public void message(int id, int errorCode, String errorMsg, String advancedORderRejectJson) {
        outputToGeneral(usTime(),
                "DefaultConnHandler error ID:", id, "code:", errorCode, "msg:", errorMsg, "rejectJson:", advancedORderRejectJson);
    }

    @Override
    public void show(String string) {
        pr(" show string " + string);
    }


}

