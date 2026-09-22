public class SensorRequest {
    private String key;
    private String team;
    private String sensor;
    private String mac;
    private double temp;
    private double humidity;
    private double AQI;
    private double TVOC;
    private double eCO2;
    private long timestamp;
    private double lat;
    private double lon;
    private String sender;

    public SensorRequest(String key, String team, String sensor, String mac,
                         double temp, double humidity, double AQI, double TVOC, double eCO2,
                         long timestamp, double lat, double lon, String sender) {
        this.key = key;
        this.team = team;
        this.sensor = sensor;
        this.mac = mac;
        this.temp = temp;
        this.humidity = humidity;
        this.AQI = AQI;
        this.TVOC = TVOC;
        this.eCO2 = eCO2;
        this.timestamp = timestamp;
        this.lat = lat;
        this.lon = lon;
        this.sender = sender;
    }
}