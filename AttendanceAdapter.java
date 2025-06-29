package com.example.classroomapp;

//package com.example.presence;



import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AttendanceAdapter extends ArrayAdapter<AttendanceRecord> {
    private final LayoutInflater inflater;

    public AttendanceAdapter(Context context, List<AttendanceRecord> records) {
        super(context, 0, records);
        inflater = LayoutInflater.from(context);
    }

    @NonNull
    @SuppressLint("SetTextI18n")
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        AttendanceRecord record = getItem(position);
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.attendance_item, parent, false);
        }
        assert record != null;
        ((TextView) convertView.findViewById(R.id.tvName))
                .setText("Name: " + record.getName());
        ((TextView) convertView.findViewById(R.id.tvDeviceId))
                .setText("Device ID: " + record.getDeviceId());
        ((TextView) convertView.findViewById(R.id.tvEmail))
                .setText("Email: " + record.getEmail());
        ((TextView) convertView.findViewById(R.id.tvTimestamp))
                .setText("Time: " + formatTimestamp(record.getTimestamp()));
        ((TextView) convertView.findViewById(R.id.tvDistance))
                .setText(String.format(Locale.getDefault(),
                        "Distance: %.2f meters", record.getDistance()));
        return convertView;
    }

    private String formatTimestamp(long millis) {
        return new SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault())
                .format(new Date(millis));
    }
}


