import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class RandomTest {

    public static void main(String[] args) {
        try (BufferedWriter out = new BufferedWriter(new FileWriter("trading/TradingFiles/ABC", true))) {
            out.append("blah blah");
            out.newLine();
        } catch (
                IOException ex) {
            ex.printStackTrace();
        }
    }

}
