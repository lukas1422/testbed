package handler;

import controller.ApiController;
import Trader.TradingUtility;

import java.util.List;

import static api.TradingConstants.simpleHourMinuteSec;
import static utility.Utility.pr;

public class DefaultConnectionHandler implements ApiController.IConnectionHandler {
    @Override
    public void connected() {
        pr("Default Conn Handler: connected");
    }

    @Override
    public void disconnected() {
        pr("Default Conn Handler: disconnected");
    }

    @Override
    public void accountList(List<String> list) {
        pr("account list ", list);
    }

    @Override
    public void error(Exception e) {
        pr(" error in iconnectionHandler");
        e.printStackTrace();
    }

    @Override
    public void message(int id, int errorCode, String errorMsg, String advancedORderRejectJson) {
        pr(TradingUtility.getESTLocalTimeNow().format(simpleHourMinuteSec),
                " DefaultConnHandler error ID:" + id + " code:" + errorCode + " msg:" + errorMsg);
    }

    @Override
    public void show(String string) {
        pr(" show string " + string);
    }


}

