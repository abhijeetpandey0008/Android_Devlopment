package com.example.classroomapp;

//package com.example.presence;



public class AttendanceRecord {
    private final String name;
    private final String deviceId ;
    private final String email ;
    private final long timestamp;
    private final float distance;

    public AttendanceRecord(String name, String deviceId, String email, long timestamp, float distance) {
        this.name = name;
        this.deviceId = deviceId;
        this.email = email;
        this.timestamp = timestamp;
        this.distance = distance;
    }

    public String getName() { return name; }
    public String getDeviceId() { return deviceId; }
    public String getEmail() { return email; }
    public long getTimestamp() { return timestamp; }
    public float getDistance() { return distance; }
}


