package Trader;

import client.*;
import controller.ApiController;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import utility.Utility;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static Trader.Allstatic.*;
import static Trader.TradingUtility.*;
import static api.TradingConstants.MdHmm;
import static api.TradingConstants.Hmmss;
import static utility.Utility.*;
import static utility.Utility.pr;

public class MarketDataTest implements LiveHandler {

    private static ApiController apiController;

    public static final int GATEWAY_PORT = 4001;
    public static final int TWS_PORT = 7496;
    public static final int PORT_TO_USE = GATEWAY_PORT;

    static Contract tencent = generateHKStockContract("700");
    static Contract wmt = generateUSStockContract("WMT");


    private MarketDataTest() {
    }

    private void connectAndReqPos() {
        ApiController ap = new ApiController(new DefaultConnectionHandler(), new Utility.DefaultLogger(), new Utility.DefaultLogger());
        apiController = ap;
        CountDownLatch l = new CountDownLatch(1);

        try {
            ap.connect("127.0.0.1", PORT_TO_USE, 5, "");
            l.countDown();
            pr(" Latch counted down 4001 " + getESTDateTimeNow().format(MdHmm));
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }


        try {
            l.await();
            pr("connected");
        } catch (InterruptedException e) {
            outputToGeneral("error in connection:", e);
        }


        es.schedule(() -> {
//            pr("Position end: requesting live:", ibContractToSymbol(wmt));
            req1ContractLive(apiController, tencent, this, false);
        }, 10L, TimeUnit.SECONDS);
    }


    //live data start
    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symb = ibContractToSymbol(ct);

        switch (tt) {
            case LAST:
                pr("last::", symb, price, t.format(Hmmss));
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
    }

    @Override
    public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {
    }

    @Override
    public void handleString(TickType tt, String symbol, String str, LocalDateTime t) {
    }


    //open orders end **********************
    public static void main(String[] args) {
        MarketDataTest test1 = new MarketDataTest();
        test1.connectAndReqPos();

//        testTrade(wmt, 250, getESTLocalDateTimeNow(), Decimal.get(5));
//        testTrade(wmt, 100, getESTLocalDateTimeNow(), Decimal.get(1));
    }

}
