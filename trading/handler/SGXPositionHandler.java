package handler;

import client.Contract;
import client.Decimal;
import controller.ApiController;
import historical.HistChinaStocks;

import javax.swing.*;

public class SGXPositionHandler implements ApiController.IPositionHandler {
        @Override
        public void position(String account, Contract contract, Decimal position, double avgCost) {
            String ticker = utility.Utility.ibContractToSymbol(contract);
            SwingUtilities.invokeLater(() -> {
                HistChinaStocks.currentPositionMap.put(ticker, position);
            });
        }

    @Override
    public void positionEnd() {
        //System.out.println(" position end ");
    }
}
