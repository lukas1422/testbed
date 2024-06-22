package Trader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static Trader.TradingUtility.generateUSStockContract;
import static api.TradingConstants.RELATIVEPATH;
import static utility.Utility.pr;

public class ClearOutput {
    private ClearOutput() throws IOException {

        Files.lines(Paths.get(RELATIVEPATH + "interestListUS")).map(l -> l.split(" "))
                .forEach(a -> {
                    //pr("whole line", a);
                    pr("a[0]", a[0]);
                    String stockName = a[0].equalsIgnoreCase("BRK") ? "BRK B" : a[0];
                    pr(stockName);
//                    Files.newBufferedWriter(RELATIVEPATH + stockName, StandardOpenOption.TRUNCATE_EXISTING);
                    try {
//                        if (stockName.equalsIgnoreCase("KO")) {
                        PrintWriter writer = new PrintWriter(RELATIVEPATH + stockName);
                        writer.print("");
                        writer.close();
//                        }
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }

                });


    }

    public static void main(String[] args) throws IOException {
        ClearOutput co = new ClearOutput();

    }
}
