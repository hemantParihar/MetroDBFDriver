import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class JarSmokeTest {
    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:dbf:E:/METRO/PA25");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS total FROM master")) {
            rs.next();
            System.out.println("Driver auto-registered via ServiceLoader; COUNT(*) = " + rs.getLong(1));
        }
    }
}
