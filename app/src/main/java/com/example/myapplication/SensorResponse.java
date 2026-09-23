package com.example.myapplication;

public class SensorResponse {
    private String result;
    private String message;
    private ReceivedData received_data;

    public String getResult() { return result; }
    public String getMessage() { return message; }
    public ReceivedData getReceivedData() { return received_data; }

    public static class ReceivedData {
        private String team;
        private String sensor;

        public String getTeam() { return team; }

        public String getSensor() { return sensor;}
    }
}