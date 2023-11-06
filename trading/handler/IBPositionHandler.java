package handler;

import api.ChinaPosition;
import client.Contract;
import client.Decimal;
import controller.ApiController;

import static utility.Utility.ibContractToSymbol;

public class IBPositionHandler implements ApiController.IPositionHandler {

    @Override
    public void position(String account, Contract contract, Decimal position, double avgCost) {
        String symbol = ibContractToSymbol(contract);
        ChinaPosition.currentPositionMap.put(symbol, position);



        //testing for live breach here


    }

    @Override
    public void positionEnd() {
        ChinaPosition.currentPositionMap.keySet().forEach(k->{

        });

    }

}
