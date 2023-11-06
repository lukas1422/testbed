package handler;

import Trader.Allstatic;
//import api.ChinaStock;
import auxiliary.SimpleBar;
import client.Contract;
import client.TickType;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import static utility.Utility.ibContractToSymbol;

public interface LiveHandler extends GeneralHandler {
    void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t);

    void handleVol(TickType tt, String symbol, double vol, LocalDateTime t);

    void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t);

    class PriceMapUpdater implements LiveHandler {
        @Override
        public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
            String symbol = ibContractToSymbol(ct);
            if (tt == TickType.LAST) {
                Allstatic.priceMap.put(symbol, price);
            } else if (tt == TickType.CLOSE) {
                Allstatic.closeMap.put(symbol, price);
            } else if (tt == TickType.OPEN) {
                Allstatic.openMap.put(symbol, price);
            }

        }

        @Override
        public void handleVol(TickType tt, String symbol, double vol, LocalDateTime t) {
        }

        @Override
        public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {
        }
    }

    class DefaultLiveHandler implements LiveHandler {
        @Override
        public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
            String symbol = ibContractToSymbol(ct);
            LocalTime lt = t.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
            if (tt == TickType.LAST) {
                Allstatic.priceMap.put(symbol, price);
                if (Allstatic.priceMapBar.get(symbol).containsKey(lt)) {
                    Allstatic.priceMapBar.get(symbol).get(lt).add(price);
                } else {
                    Allstatic.priceMapBar.get(symbol).put(lt, new SimpleBar(price));
                }
            } else if (tt == TickType.CLOSE) {
                Allstatic.closeMap.put(symbol, price);
                if (Allstatic.priceMap.getOrDefault(symbol, 0.0) == 0.0) {
                    Allstatic.priceMap.put(symbol, price);
                }
            } else if (tt == TickType.OPEN) {
                Allstatic.openMap.put(symbol, price);
            }
        }

        @Override
        public void handleVol(TickType tt, String symbol, double vol, LocalDateTime t) {
        }

        @Override
        public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {

        }
    }
}
