import java.io.*;
import org.json.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main Class quality assurance processing
 *
 * @Author Ross Newby
 */
public class ServerImpl extends UnicastRemoteObject implements ServerInterface {

    /*Variables used for metadata and other JSONs*/
    private JSONObject packageJSON = null; // JSON objects for all metadata
    private JSONObject meterJSON = null;
    private JSONObject loggerJSON = null;
    private Thread meterThread, loggerThread; // threads for initial metadata reading
    private JSONObject readingJSON = null; // Global JSON object for reading multiple requests in threads

    /*Used to access CKAN and other files, if paths / names change; amend them here*/
    private static final String DB_INIT_FILEPATH = "src/eisqualityinit.sql"; // mysql database initialisation file
    private static final String DB_HOST = "jdbc:mysql://localhost:3306/eisquality";
    private static final String METER_SENSOR_METADATA_NAME = "Planon metadata - Meters Sensors"; // names of metadata files in CKAN
    private static final String LOGGER_CONTROLLER_METADATA_NAME = "Planon metadata - Loggers Controllers";
    private static final String BMS_CLASSIFICATION_GROUP = "Energy meter"; // identifier for EMS records
    private static final String EMS_CLASSIFICATION_GROUP = "Energy sensor"; // identifier for BMS records

    private Database database = null; // mysql database
    private Scanner scanner = new Scanner(System.in); // used for basic console line input
    private String input = null;

    /**
     * Initialises a server by reading basic authentication details and API data from config file. Also queries CKAN datastore for all
     * metadata and returns once JSON objects have been read completely. EIS Quality database in MySQL is initialised.
     * @throws RemoteException Problem during remote procedure call
     * @throws FileNotFoundException If config.properties file not found in root directory
     */
    public ServerImpl() throws RemoteException{

        System.out.println("Initialising Server...");

        /*Get CKAN metadata*/
        try {
            CKANRequest ckanReq = new CKANRequest("ckan.lancaster.ac.uk/api/3/action/package_show?id=planonmetadata");
            packageJSON = ckanReq.requestJSON();
            JSONArray packageList = packageJSON.getJSONObject("result").getJSONArray("resources"); // Array of resource names available in CKAN

            String lookingFor = METER_SENSOR_METADATA_NAME; // search for meter / sensor metadata
            for (int i = 0; i < packageList.length(); i++) { // for every package name in CKAN
                if (packageList.getJSONObject(i).getString("name").equals(lookingFor)){ // if package is meter / sensor data
                    // Get package ID number and use this in another CKAN request for meter / sensor metadata
                    String id = packageList.getJSONObject(i).getString("id");
                    meterThread = new Thread() {
                        public void run() {
                            try {
                                CKANRequest ckanReq = new CKANRequest("ckan.lancaster.ac.uk/api/3/action/datastore_search_sql?sql=SELECT%20*%20FROM%20\"" + id + "\"");
                                meterJSON = ckanReq.requestJSON();
                                //meterCodes = getSpecified(meterJSON, "Logger Asset Code");
                            }
                            catch (Exception e){
                                e.printStackTrace();
                            }
                        }
                    };
                    meterThread.start();
                }
            }
            lookingFor = LOGGER_CONTROLLER_METADATA_NAME; // Search for logger / controller metadata
            for (int i = 0; i < packageList.length(); i++) { // for every package name in CKAN
                if (packageList.getJSONObject(i).getString("name").equals(lookingFor)){ // if package is loggers / controllers
                    // Get package ID number and use this in another CKAN request for logger / controller metadata
                    String id = packageList.getJSONObject(i).getString("id");
                    loggerThread = new Thread() {
                        public void run() {
                            try {
                                CKANRequest ckanReq = new CKANRequest("ckan.lancaster.ac.uk/api/3/action/datastore_search_sql?sql=SELECT%20*%20FROM%20\"" + id + "\"");
                                loggerJSON = ckanReq.requestJSON();
                                //loggerCodes = getSpecified(loggerJSON, "Logger Serial Number");
                            }
                            catch (Exception e){
                                e.printStackTrace();
                            }
                        }
                    };
                    loggerThread.start();
                }
            }
        }
        catch (NullPointerException e){
            System.out.println("Error Processing JSON String: CKAN Response 'null'");
            e.printStackTrace();
        }
        catch (Exception e){
            System.out.println("Error Processing JSON String:");
            e.printStackTrace();
        }

        /*Join threads; threads must be complete when constructor ends*/
        try {
            meterThread.join();
            loggerThread.join();
        }
        catch (Exception e){
            System.out.println("Metadata Threads Interrupted:");
            e.printStackTrace();
        }

        /*Connect to Database*/
        database = new Database(DB_HOST);
        try {
            database.executeSQLScript(DB_INIT_FILEPATH);
        }
        catch (Exception e) {
            System.out.println("Could not initialise DB, likely problem with 'eisqualityinit.sql' file");
            e.printStackTrace();
        }
        database.printDatabase(); //debug

        System.out.println("Server Initialised"); // confirmation message
        serverMenu();
    }

    /**
     *
     */
    private void serverMenu(){

        while (!"9".equals(input)) {
            INVALID:
            {
                System.out.println("-- Actions --");
                System.out.println("Select an Action:\n" + // TODO These aren't final options, they're just needed for development
                                    "  1) Print Database\n" +
                                    "  2) Test Metadata Quality\n" +
                                    "  3) Test All Data Quality\n" +
                                    "  4) Test Specific Logger\n" +
                                    "  5) Test Specific Meter\n" +
                                    "  9) Disable Actions");
                input = scanner.nextLine();

                if ("1".equals(input)){ // Print Database
                    database.printDatabase();
                }
                else if ("2".equals(input)){ // Test Metadata for errors
                    testMetadata();
                }
                else if ("3".equals(input)){
                    //...
                }
                else if ("4".equals(input)){
                    testLogger("{05937EE0-58E6-42F3-B6BD-A180D9634B6C}");
                }
                else if ("5".equals(input)){
                    testMeter("{05937EE0-58E6-42F3-B6BD-A180D9634B6C}", "D1");
                    //getMeterJSON("{05937EE0-58E6-42F3-B6BD-A180D9634B6C}", "D1"); //testing only
                }
                else if ("9".equals(input)){ // Disable console inputs
                    System.out.println("Actions no longer available");
                }
                else if ("e".equals(input)) { // Close application
                    exit();
                }
                else { // Invalid Input
                    System.out.println("Invalid Option.");
                }
            }
        }
    }

    /**
     * Tests the CKAN metadata for errors and adds any detected errors to the sql database
     */
    public void testMetadata() {
        System.out.println("Processing Metadata..."); //debug
        String[] loggerCodes, meterCodes;
        String[] loggerDescs, meterDescs;

        /*All logger codes in logger metadata*/
        ArrayList<String> loggerCodeArray = new ArrayList<>();
        ArrayList<String> loggerDescArray = new ArrayList<>();
        JSONArray loggerList = loggerJSON.getJSONObject("result").getJSONArray("records"); //list of loggers
        for (int i = 0; i < loggerList.length(); i++) { // every logger
            String name = loggerList.getJSONObject(i).getString("Logger Serial Number"); //store logger code
            loggerCodeArray.add(name);
            String desc = loggerList.getJSONObject(i).getString("Description"); //store description
            loggerDescArray.add(desc);
        }
        loggerCodes = convertToArray(loggerCodeArray); // convert to regular String arrays
        loggerDescs = convertToArray(loggerDescArray);

        /*All logger codes in meter metadata*/
        ArrayList<String> meterArray = new ArrayList<>();
        ArrayList<String> meterDescArray = new ArrayList<>();
        JSONArray meterList = meterJSON.getJSONObject("result").getJSONArray("records"); //list of meters
        for (int i = 0; i < meterList.length(); i++) { // every meter
            String name = meterList.getJSONObject(i).getString("Logger Asset Code"); // store logger code
            meterArray.add(name);
            String desc = meterList.getJSONObject(i).getString("Description"); //store description
            meterDescArray.add(desc);
        }
        meterCodes = convertToArray(meterArray); // convert to regular String arrays
        meterDescs = convertToArray(meterDescArray);

        System.out.println("Loggers Codes: " + loggerCodes.length + ", Meter Codes: " + meterCodes.length); //debug

        /*Test for loggers without meters*/
        int errors = 0;
        for (int i = 0; i < loggerCodes.length; i++){ // for every logger
            boolean found = false;
            for (int j = 0; j < meterCodes.length; j++){ // look at every meter
                if (loggerCodes[i].equals(meterCodes[j])) {
                    found = true;
                    break; // break if meter matching logger is found
                }
            }
            if (!found){
                errors++;
                Map<String, String> vals = new HashMap<>();
                vals.put("error_type", "\"logger no meter\"");
                vals.put("asset_code", "\""+loggerCodes[i]+"\"");
                vals.put("description", "\""+loggerDescs[i]+"\"");
                database.addRecord("metadataerrors", vals); // Add error to database
            }
        }
        System.out.println("Loggers without Meters: " + errors);

        /*Test for meters without loggers*/
        errors = 0;
        for (int i = 0; i < meterCodes.length; i++){ // for every meter
            boolean found = false;
            for (int j = 0; j < loggerCodes.length; j++){ // look at every logger
                if (meterCodes[i].equals(loggerCodes[j])) {
                    found = true;
                    break; // break if logger matching meter is found
                }
            }
            if (!found){
                errors++;
                Map<String, String> vals = new HashMap<>();
                vals.put("error_type", "\"meter no loggers\"");
                vals.put("asset_code", "\""+meterCodes[i]+"\"");
                vals.put("description", "\""+meterDescs[i]+"\""); //TODO Improve descriptions (account for blanks and expand desc)
                database.addRecord("metadataerrors", vals); // Add error to database
            }
        }
        System.out.println("Meters without Loggers: " + errors);
        System.out.println("Metadata Processing Successful");
    }

    /**
     * Tests a BMS / EMS logger for errors and adds any detected errors to the sql database
     * @param loggerCode Logger's unique code
     */
    public void testLogger(String loggerCode){
        /*repeatedly test meters from a logger using testMeter method*/
        JSONObject meterJSON = getMeterJSON(loggerCode, "D1");
        JSONArray meterArr = meterJSON.getJSONArray("records");
        try {
            writeFile(meterArr, "Testing-" + loggerCode);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Tests meter for errors and adds any detected errors to the sql database
     * @param loggerCode Meters / sensor's logger code
     * @param moduleKey Meter / sensor's module key
     */
    public void testMeter(String loggerCode, String moduleKey){

        /*Get all Meter reading from CKAN*/
        JSONObject meterJSON = getMeterJSON(loggerCode, moduleKey);
        JSONArray meterArray = meterJSON.getJSONArray("records");
        System.out.println("Arrays in meterJSON: " + meterArray.length());

        /*Add each JSONObject from meter into a list*/
        List<JSONObject> jsonValues = new ArrayList<>();
        for (int i = 1; i < meterArray.length(); i++) { //TODO change looping when getMeterJSON returns one singular array
            JSONArray jsonArray = meterArray.getJSONArray(i);
            System.out.println("Arrays in jsonArray: " + jsonArray.length());
            for (int j = 0; j < jsonArray.length(); j++) {
                jsonValues.add(jsonArray.getJSONObject(j));
            }
        }

        /*Sort the meter readings by their timestamp*/
        Collections.sort( jsonValues, new Comparator<JSONObject>() {

            private static final String KEY_NAME = "timestamp"; // key to sort JSONArray by

            @Override
            public int compare(JSONObject a, JSONObject b) {
                String valA = new String();
                String valB = new String();

                try {
                    valA = a.getString(KEY_NAME);
                    valB = b.getString(KEY_NAME);
                }
                catch (JSONException e) {
                    System.out.println("Error: No value for key '"+KEY_NAME+"' in JSONObject");
                }

                return valA.compareTo(valB); // change to - to change sort order
            }
        });

//        /**/
//        JSONArray sortedMeterArray = new JSONArray();
//        for (int i = 0; i < meterArray.length(); i++) {
//            sortedMeterArray.put(jsonValues.get(i));
//        }

        /*Debug: print list for verification*/
        try {
            writeFile(jsonValues, "Sorted-" + loggerCode);
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        /*Check whether meter has recent data*/
        //...

        /*Check Quality of reading: -ve data, no data etc.*/
        //...

        /*Check time interval of readings is correct*/
        //...
    }

    /**
     * Convert all data for a single meter / sensor to a single JSON object.
     * Both the logger code and module key make a unique identifier for the Meter
     * @param loggerCode Meters / sensor's logger code
     * @param moduleKey Meter / sensor's module key
     * @return JSON of all data for the specified meter
     */
    public JSONObject getMeterJSON(String loggerCode, String moduleKey){
        readingJSON = null; // reset JSONObject for reading; precaution
        try {
            /*List of BMS files in CKAN*/
            CKANRequest ckanReq = new CKANRequest("ckan.lancaster.ac.uk/api/3/action/package_show?id=bms");
            JSONObject bmsJSON = ckanReq.requestJSON();
            JSONArray bmsList = bmsJSON.getJSONObject("result").getJSONArray("resources"); // Array of bms files in CKAN (JSON Objects)
            ArrayList<String> fileList = new ArrayList<>(); // list of BMS filenames in CKAN
            for (int i = 0; i < bmsList.length(); i++){ // each BMS file
                String fileName = bmsList.getJSONObject(i).getString("name"); // next BMS filename
                if (!fileName.equals("bmsdevicemeta")) {
                    if (!fileName.equals("bmsmodulemeta")) {// don't include bms metadata in list
                        if(fileName.contains("2017") || fileName.equals("bms-dec-2016")) { //TODO Account for older 2016 data
                            fileList.add(bmsList.getJSONObject(i).getString("id"));
                            System.out.println("Added: " + fileName + ", " + bmsList.getJSONObject(i).getString("id")); //debug
                        }
                    }
                }
            }

            String[] bmsFileIDs = convertToArray(fileList); // convert list of file IDs to regular array
            readingJSON = new JSONObject(); // empty JSON to repeatedly update with meter data

            /*Get data for meter from every bms file*/
            ExecutorService es = Executors.newCachedThreadPool();
            for (String fileID: bmsFileIDs) { // for every bms file
                es.execute(new Thread() { // execute code on new thread
                    public void run() {
                        try {
                            /*Accumulate all data for the meter from each file*/
                            CKANRequest ckanReq = new CKANRequest("ckan.lancaster.ac.uk/api/3/action/datastore_search_sql?sql=SELECT%20*%20FROM%20\""
                                                                    + fileID + "\"%20WHERE%20device_id='"+loggerCode+"'%20AND%20module_key='"+moduleKey+"'");
                            JSONObject newJSON = ckanReq.requestJSON(); // JSON object of meter data from this file
                            JSONArray toAccumulate = newJSON.getJSONObject("result").getJSONArray("records"); // records from JSON object to append
                            readingJSON.accumulate("files", fileID); // append file id of current file to the new wJSON object
                            readingJSON.accumulate("records", toAccumulate); // append meter data to the new JSON object
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

            /*Wait for all thread to end*/
            System.out.println("Waiting on Threads for " + loggerCode + "-" + moduleKey + "...");
            es.shutdown();
            es.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            System.out.println("All Threads Complete for " + loggerCode + "-" + moduleKey);

            writeFile(readingJSON, loggerCode+"-"+moduleKey); //Debug; output result to file for debug
        }
        catch (Exception e){
            e.printStackTrace();
        }
        return readingJSON;
    }

    /**
     * A list of building names available in the CKAN metadata
     * @return Array of building names
     */
    public String[] getBuildingList() {
        ArrayList<String> outputList = new ArrayList<>();

        JSONArray loggerList = loggerJSON.getJSONObject("result").getJSONArray("records"); //list of loggers / controllers
        for (int i = 0; i < loggerList.length(); i++) { // loop through every logger
            String name = loggerList.getJSONObject(i).getString("Building Name");
            if (!outputList.contains(name)){
                outputList.add(name); // add building name of logger to return list, if it hasnt been seen already
            }
        }

        /*Convert ArrayList to String array*/
        String[] outputArray = new String[outputList.size()];
        outputList.toArray(outputArray);
        return outputArray;
    }

    /**
     * A set of elements from a specified JSON attribute; duplicate elements are ignored
     * @param JSONObj JSON object to search records
     * @param toFind Element name to find
     * @return List of unique element names from JSONObject
     */
    public String[] getSpecified(JSONObject JSONObj, String toFind) {
        ArrayList<String> outputList = new ArrayList<>();
        JSONArray objList = JSONObj.getJSONObject("result").getJSONArray("records"); // list of JSONObjects
        for (int i = 0; i < objList.length(); i++) { // loop through every object
            String name = objList.getJSONObject(i).getString(toFind);
            if (!outputList.contains(name)){
                outputList.add(name); // add element name to output
            }
        }
        if (outputList.size() == 0) {
            System.out.println("WARNING: No elements found for " + toFind + "in JSONObject");
        }
        /*Convert ArrayList to String array*/
        String[] outputArray = new String[outputList.size()];
        outputArray = outputList.toArray(outputArray);
        return outputArray;
    }

    /**
     * Converts an ArrayList<String> to regular String[] array
     * @param in ArrayList to convert
     * @return Regular String[] array of input
     */
    private String[] convertToArray(ArrayList<String> in){
        String[] ret = new String[in.size()]; // convert list of filenames to regular String[] array
        in.toArray(ret);
        return ret;
    }

    /**
     * Converts text file to String; used for testing. Code from:
     * https://stackoverflow.com/questions/326390/how-do-i-create-a-java-string-from-the-contents-of-a-file
     * @param path Path to file to be converted
     * @param encoding Charset encoding typically StandardCharsets.UTF_8
     * @return String of converted text
     */
    private static String readFile(String path, Charset encoding) throws IOException {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }

    /**
     * Converts a JSONObject to String and writes it to a text file
     * @param obj JSONObject to write to file
     * @param name Name of text file (excluding '.txt'
     */
    private void writeFile(Object obj, String name) throws IOException{
        String fileName = name+".txt";
        try (FileWriter file = new FileWriter(fileName)) {
            file.write(obj.toString());
            System.out.println("Successfully Copied JSON Object to File: " + fileName);
        }
    }

    /**
     * Terminates application
     */
    private void exit(){
        System.out.println("Exiting...");
        System.exit(1);
    }
}
