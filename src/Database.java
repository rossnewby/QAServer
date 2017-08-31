import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;

/**
 * @Author Ross Newby
 */
public class Database {

    private static final String URL = "jdbc:mysql://localhost:3306/eisquality";
    private static String USER = "not set";
    private static String PASSWORD = "not set";
    static private final int PAD_SIZE = 25;

    private Connection con;
    private Statement st;
    private ResultSet rs;

    public Database(){

        /*Read configuration file; populate variables*/
        try {
            Properties prop = new Properties();
            String propFileName = "config.properties";
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream(propFileName);

            if (inputStream != null) {
                prop.load(inputStream);
            } else {
                throw new FileNotFoundException("'" + propFileName + "' not found in classpath");
            }

            USER = prop.getProperty("mysqluser");
            PASSWORD = prop.getProperty("mysqlpass");
        }
        catch (Exception e){
            System.out.println("Error Reading Configuration File:");
            e.printStackTrace();
        }

        /*Connect to MySQL database*/
        try {
            Class.forName("com.mysql.jdbc.Driver");

            con = DriverManager.getConnection(URL+"?autoReconnect=true&useSSL=false", USER, PASSWORD);
            st = con.createStatement();
        }
        catch (Exception e){
            System.out.println("MySQL Error:");
            e.printStackTrace();
        }
    }

    /**
     *
     * @param tableName
     * @param values
     */
    public void addRecord(String tableName, String values){

        try {
            String query = ("INSERT INTO "+ tableName + " VALUES (default, " + values + ")");
            //System.out.println("Executing >> " + query); //debug
            st.executeUpdate(query);
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     *
     * @param tableName
     * @param values
     */
    public void addRecord(String tableName, Map<String, String> values){
        try {
            StringBuilder columnNames = new StringBuilder();
            StringBuilder valueNames = new StringBuilder();

            columnNames.append(" (");
            valueNames.append(" (");
            for (String key: values.keySet()){
                columnNames.append(key + ", ");
                valueNames.append(values.get(key) + ", ");
            }
            columnNames.setLength(columnNames.length()-2);
            columnNames.append(") ");
            valueNames.setLength(valueNames.length()-2);
            valueNames.append(") ");

            String query = ("INSERT INTO "+ tableName + columnNames + "VALUES" + valueNames);
            //System.out.println("Executing >> " + query); //debug
            st.executeUpdate(query);
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    // https://coderanch.com/t/306966/databases/Execute-sql-file-java by Tom Enders

    /**
     * Executes a set of SQL statements from file. Adaptation from code by Tom Enders:
     * https://coderanch.com/t/306966/databases/Execute-sql-file-java
     * @param path File path to SQL scripts e.g <path to folder>/scripts.sql
     * @throws SQLException
     */
    public void executeSQLScript(String path) throws SQLException {

        try {
            FileReader fr = new FileReader(new File(path));
            // be sure to not have line starting with "--" or "/*" or any other non aplhabetical character

            BufferedReader br = new BufferedReader(fr);
            String s;
            StringBuffer sb = new StringBuffer();

            while((s = br.readLine()) != null)
            {
                sb.append(s);
            }
            br.close();

            // here is our splitter ! We use ";" as a delimiter for each request
            // then we are sure to have well formed statements
            String[] inst = sb.toString().split(";");

            for(int i = 0; i<inst.length; i++)
            {
                // we ensure that there is no spaces before or after the request string
                // in order to not execute empty statements
                if(!inst[i].trim().equals(""))
                {
                    st.executeUpdate(inst[i]);
                    // System.out.println(">>"+inst[i]); // print commands as they execute
                }
            }
        }
        catch(Exception e) {
            System.out.println("DB Error:");
            e.printStackTrace();
        }

    }

    /**
     * Prints all the tables in the database; used for debugging and referencing
     */
    public void printDatabase() {
        //find out what all the table names in the database are
        ArrayList<String> tables = new ArrayList<String>();
        try {
            DatabaseMetaData md = con.getMetaData();

            String[] types = {"TABLE"};
            ResultSet rs = md.getTables(null, null, null, types);

            while (rs.next()) { //read in all tables in the database
                tables.add(rs.getString(3));
            }

            rs.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int i = 0; i < tables.size(); i++) {
            ArrayList<String> cols = new ArrayList<>();

            try {
                Statement sqlStmt = con.createStatement();

                String sqlCmdStr = ("select * from " + tables.get(i));
                ResultSet rSet = sqlStmt.executeQuery(sqlCmdStr);
                ResultSetMetaData mData = rSet.getMetaData();

                System.out.println("Table: " + mData.getTableName(2));

                int rowCount = mData.getColumnCount();

                //read in all the column names
                for (int j = 1; j < rowCount + 1; j++) {
                    cols.add(mData.getColumnName(j));
                }

                //print topmost border
                for (int j = 0; j < cols.size(); j++) {
                    System.out.print(" + " +pad(""));
                }
                System.out.println(" + ");

                //print the names of the columns in the table
                for (int j = 0; j < cols.size(); j++) {
                    System.out.print(" | " + pad(cols.get(j)));
                }
                System.out.println(" | ");

                //print middle border
                for (int j = 0; j < cols.size(); j++) {
                    System.out.print(" + " + pad(""));
                }
                System.out.println(" + ");

                //read and print every row in the table
                while (rSet.next()) {
                    for (int j = 0; j < cols.size(); j++) {
                        String item = rSet.getString(cols.get(j));
                        System.out.print(" | " + pad(item));
                    }
                    System.out.println(" | ");
                }

                //print bottom border
                for (int j = 0; j < cols.size(); j++) {
                    System.out.print(pad("") + " + ");
                }
                System.out.println("");

                rSet.close();
                sqlStmt.close();

            } catch (SQLException e) {
                e.printStackTrace();
            }
            System.out.println("");
        }
    }

    // this method takes a String, converts it into an array of bytes;
    // copies those bytes into a bigger byte array (STR_SIZE worth), and
    // pads any remaining bytes with spaces. Finally, it converts the bigger
    // byte array back into a String, which it then returns.
    // e.g. if the String was "s_name", the new string returned is
    // "s_name                    " (the six characters followed by 18 spaces).
    private String pad(String in) {
        byte[] org_bytes = in.getBytes();
        byte[] new_bytes = new byte[PAD_SIZE];
        int upb = in.length();

        if (upb > PAD_SIZE)
            upb = PAD_SIZE;

        for (int i = 0; i < upb; i++) {
            new_bytes[i] = org_bytes[i];
        }

        for (int i = upb; i < PAD_SIZE; i++) {
            new_bytes[i] = (byte) ((in.equals("")) ? '-' : ' ');
        }

        return new String(new_bytes);
    }
}
