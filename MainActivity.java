package com.example.classroomapp;


import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.classroom.Classroom;
import com.google.api.services.classroom.model.Course;
import com.google.api.services.classroom.model.Student;
import androidx.appcompat.app.AlertDialog;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.http.HttpTransport;



import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
public class MainActivity extends AppCompatActivity implements WifiP2pManager.ConnectionInfoListener {
    private static final String TAG = "MainActivity";
    private static final int REQ_PERMS = 101;
    private static final int RC_SIGN_IN_HOST = 9001;
    private static final int RC_SIGN_IN_CLIENT = 9002;
    private static final int MAX_RETRIES = 3; // Maximum retries for API calls

    // Permissions for API ≤32
    private static final String[] PERMS_PRE_33 = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };
    // Permissions for API ≥33
    private static final String[] PERMS_33_PLUS = {
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION    // still needed for GPS
    };
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private WiFiDirectBroadcastReceiver receiver;
    private IntentFilter filter;
    private boolean isHost = false;
    private boolean isGroupOwner;
    private Location hostLocation;
    private AttendanceAdapter adapter;
    private final List<AttendanceRecord> attendanceList = new ArrayList<>();
    // Round-robin queue & cache
    private final LinkedList<WifiP2pDevice> deviceQueue = new LinkedList<>();
    private final List<AttendanceRecord> cycleCache = new ArrayList<>();
    private static final int BATCH_SIZE = 7;
    private static final long BATCH_TIMEOUT_MS = 15_000; // 10 seconds
    private final Handler cycleHandler = new Handler(Looper.getMainLooper());
    private boolean cycleRunning = false;
    private int expectedBatchCount = 0;
    private int receivedCount = 0;
    private Runnable finishRunnable;
    private static final int SERVER_PORT = 8888;
    private ServerSocket serverSocket;
    private ExecutorService clientExecutor;

    // Google Sign-In and Classroom API variables
    private GoogleSignInClient signInClientHost;
    private GoogleSignInClient signInClientClient;
    private GoogleSignInAccount signedInAccount;
    private final List<String> studentEmails = new ArrayList<>();
    private final List<Course> teacherCourses = new ArrayList<>();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- UI setup ---
        ListView lv = findViewById(R.id.attendance_list);
        adapter = new AttendanceAdapter(this, attendanceList);
        lv.setAdapter(adapter);
        // --- Request the correct set of permissions ---
        requestNeededPermissions();
        // --- Wi-Fi P2P init ---
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);

        // Register local service advertisement
        advertiseLocalService();
        manager.setDnsSdResponseListeners(channel,
                (instanceName, regType, srcDevice) -> {

                    if (!isHost) {
                        Log.d(TAG, "Service found: " + instanceName);
                        // Immediately connect when we see the service instance
//                    connectToDevice(srcDevice);
                        enqueueDevice(srcDevice);
                    }
                },
                (fullDomainName, txtMap, device) -> {
                    // Optionally validate TXT record before connecting
                    if (!isHost && fullDomainName.startsWith("PresenceService")
                            && "com.example.presence".equals(txtMap.get("app"))) {
//                        connectToDevice(device);
                        enqueueDevice(device);
                    }
                }
        );
        // Google Sign-In setup for teacher (host) and student (client)
        GoogleSignInOptions gsoHost = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()

                .requestScopes(new Scope("https://www.googleapis.com/auth/classroom.courses.readonly"),
                        new Scope("https://www.googleapis.com/auth/classroom.rosters.readonly"))
                .build();
        signInClientHost = GoogleSignIn.getClient(this, gsoHost);

        GoogleSignInOptions gsoClient = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();
        signInClientClient = GoogleSignIn.getClient(this, gsoClient);

        //  Buttons
        findViewById(R.id.btnHost).setOnClickListener(v -> {
            Log.d(TAG, "Host button clicked—checking canProceed()");
            hostFlow();
//            if (!canProceed()) {
//                promptPermissionOrLocation();
//                return;
//            }
//            signedInAccount = GoogleSignIn.getLastSignedInAccount(this);
//            if (signedInAccount == null || !signedInAccount.getGrantedScopes().contains(new Scope("https://www.googleapis.com/auth/classroom.courses.readonly"))) {
//                Intent signInIntent = signInClientHost.getSignInIntent();
//                startActivityForResult(signInIntent, RC_SIGN_IN_HOST);
//            } else {
//                fetchTeacherCourses();
//            }

            Log.d(TAG, "canProceed()=true; resetting to become Group Owner");

//            resetAndBecomeGroupOwner();
        });

        //  stop host button
        findViewById(R.id.btnStopHost).setOnClickListener(v -> stopHosting());
//          clint button
        findViewById(R.id.btnClient).setOnClickListener(v -> {
            Log.d(TAG, "Client button clicked—checking canProceed()");
            clientFlow();
//            if (!canProceed()) {
//                promptPermissionOrLocation();
//                return;
//            }
//            signedInAccount = GoogleSignIn.getLastSignedInAccount(this);
//            if (signedInAccount == null) {
//                Intent signInIntent = signInClientClient.getSignInIntent();
//                startActivityForResult(signInIntent, RC_SIGN_IN_CLIENT);
//            } else {
//
//                discoverServices();
//            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(receiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }
    // Request the correct runtime permissions for this API level
    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, PERMS_33_PLUS, REQ_PERMS);
        } else {
            ActivityCompat.requestPermissions(this, PERMS_PRE_33, REQ_PERMS);
        }
    }

    /**
     * Returns true if all required permissions AND Location Services are ON
     **/
    private boolean canProceed() {
        // 1) Permissions
        String[] perms = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? PERMS_33_PLUS : PERMS_PRE_33;
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        // 2) Location services (required for P2P)
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
//        return enabled;
    }
    //Prompt user to enable missing permissions or Location Services
    private void promptPermissionOrLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!enabled) {
            Toast.makeText(this, "Please enable Location Services", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else {
            Toast.makeText(this, "App needs required permissions", Toast.LENGTH_LONG).show();
            requestNeededPermissions();
        }
    }
    //  DNS-SD Service Registration
    @SuppressLint("MissingPermission")
    private void advertiseLocalService() {
        Map<String, String> record = new HashMap<>();
        record.put("app", "com.example.presence");
        WifiP2pDnsSdServiceInfo serviceInfo =
                WifiP2pDnsSdServiceInfo.newInstance(
                        "PresenceService",
                        "_presence._tcp",
                        record);
        manager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Local service registered");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed to add local service: " + reason);
            }
        });
    }
    @SuppressLint("MissingPermission")
    private void discoverServices() {
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                WifiP2pDnsSdServiceRequest req =
                        WifiP2pDnsSdServiceRequest.newInstance("_presence._tcp");
                manager.addServiceRequest(channel, req, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Service request added");
                        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                            @Override
                            public void onSuccess() {
                                Toast.makeText(MainActivity.this, "Service discovery started", Toast.LENGTH_SHORT).show();
                            }
                            @Override
                            public void onFailure(int reason) {
                                Toast.makeText(MainActivity.this, "Service discovery failed: " + reason, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "Service request failed: " + reason);
                    }
                });
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "clearServiceRequests failed: " + reason);
            }
        });
    }
    private void hostFlow() {
        if (!canProceed()) {
            promptPermissionOrLocation();
            return;
        }
        // Sign out to force account selection every time
        signInClientHost.signOut().addOnCompleteListener(this, task -> {
            Intent signInIntent = signInClientHost.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN_HOST);
        });
//        signedInAccount = GoogleSignIn.getLastSignedInAccount(this);
//        if (signedInAccount == null ||
//                !signedInAccount.getGrantedScopes().contains(new Scope("https://www.googleapis.com/auth/classroom.courses.readonly"))) {
//            startActivityForResult(signInClientHost.getSignInIntent(), RC_SIGN_IN_HOST);
//        } else {
//            fetchTeacherCourses();
//        }
    }

    private void clientFlow() {
        if (!canProceed()) {
            promptPermissionOrLocation();
            return;
        }
        // Sign out to force account selection every time
        signInClientClient.signOut().addOnCompleteListener(this, task -> {
            Intent signInIntent = signInClientClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN_CLIENT);
        });
//        signedInAccount = GoogleSignIn.getLastSignedInAccount(this);
//        if (signedInAccount == null) {
//            startActivityForResult(signInClientClient.getSignInIntent(), RC_SIGN_IN_CLIENT);
//        } else {
//            discoverServices();
//        }
    }

    // logic for Round Robin Queue
    private void enqueueDevice(WifiP2pDevice device) {
        if (!deviceQueue.contains(device)) {
            deviceQueue.add(device);
        }
        if (!cycleRunning) {
            startNextCycle();
        }
    }

    private void startNextCycle() {
        if (deviceQueue.isEmpty()) {
            cycleRunning = false;
            return;
        }
        cycleRunning = true;
        cycleCache.clear();
        receivedCount = 0;

        List<WifiP2pDevice> batch = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE && !deviceQueue.isEmpty(); i++) {
            batch.add(deviceQueue.removeFirst());
        }
        expectedBatchCount = batch.size();
        finishRunnable = () -> finishCycle(batch);
        cycleHandler.postDelayed(() -> finishCycle(batch), BATCH_TIMEOUT_MS);

        for (WifiP2pDevice dev : batch) {
            connectToDevice(dev);
        }

    }
    private void finishCycle(List<WifiP2pDevice> batch) {
//        stopHosting();
        manager.clearServiceRequests(channel, null);

        synchronized (cycleCache) {
            attendanceList.addAll(cycleCache);
        }
        runOnUiThread(() -> adapter.notifyDataSetChanged());

        startNextCycle();
    }
    @SuppressLint("MissingPermission")
    private void connectToDevice(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0;
//        tryCancelThenConnect(config, device, 0);
        // added
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "connect() initiated to " + device.deviceName);
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "connect() failed to " + device.deviceName + " (" + reason + ")");
            }
        });
    }

     // Host-side: create the P2P group and start TCP server

    private void resetAndBecomeGroupOwner() {
        //  Stop discovery
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Peer discovery stopped"); //
                removeExistingGroup();
            }
            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "stopPeerDiscovery failed: " + reason);
                removeExistingGroup();
            }
        });
    }
    private void removeExistingGroup() {
        // only require NEARBY_WIFI_DEVICES on API 33+
        boolean hasLocation = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasNearby = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNearby = ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (!hasLocation || !hasNearby) {
            Log.d(TAG, "Missing runtime permission(s) in removeExistingGroup(): "
                    + "ACCESS_FINE_LOCATION=" + hasLocation
                    + ", NEARBY_WIFI_DEVICES=" + hasNearby);
            return;
        }
        manager.requestGroupInfo(channel, group -> {
            if (group != null) {
                Log.d(TAG, "Existing group found (netId=" + group.getNetworkId() + ")");
                manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Group removed successfully.");
                        createGroupOwner();

                        // Proceed to create a new group after a short delay
//                        new Handler(Looper.getMainLooper()).postDelayed(
//                                MainActivity.this::createGroupOwner, 500);
                    }
                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "removeGroup failed: " + reason);
                        if (reason == WifiP2pManager.BUSY) {
                            // Retry after a delay with exponential backoff
                            new Handler(Looper.getMainLooper()).postDelayed(
                                    MainActivity.this::removeExistingGroup, 1000);
                        }
                    }
                });
            } else {
                Log.d(TAG, "No group to remove. Proceeding to create a new group.");
                createGroupOwner();
//                // Proceed to create a new group after a short delay
//                new Handler(Looper.getMainLooper()).postDelayed(
//                        MainActivity.this::createGroupOwner, 500);
            }
        });
    }

    private void createGroupOwner() {
        boolean hasLocation = ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasNearby = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNearby = ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (!hasLocation || !hasNearby) {
            Log.d(TAG, "Missing runtime permission(s) in createGroupOwner(): "
                    + "ACCESS_FINE_LOCATION=" + hasLocation
                    + ", NEARBY_WIFI_DEVICES=" + hasNearby);
            return;
        }
        Log.d(TAG, "Permissions OK—calling createGroup()");
        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                isGroupOwner = true;
                // added
                advertiseLocalService();
                Toast.makeText(MainActivity.this,
                        "Now Group Owner", Toast.LENGTH_SHORT).show();
                fetchHostLocation();
                runServer();
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "createGroup failed, reason=" + reason); // :contentReference[oaicite:2]{index=2}
                if (reason == WifiP2pManager.BUSY) {
                    // added
                    new Handler(Looper.getMainLooper()).postDelayed(MainActivity.this::createGroupOwner, 1000);
//                    Log.d(TAG, "Retrying createGroupOwner() due to BUSY");
//                    //  Retry if still BUSY
//                    resetAndBecomeGroupOwner();
                }
            }
        });
    }
    private void fetchHostLocation() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        hostLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
    }
    private synchronized void runServer() {
//        // Prevent binding again if it’s already open
        if (serverSocket != null && !serverSocket.isClosed()) {
            Log.d(TAG, "Server already running on port " + SERVER_PORT);
            return;
        }
        // added
        clientExecutor = Executors.newFixedThreadPool(BATCH_SIZE);
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(SERVER_PORT));
                Log.d(TAG, "Server bound on port " + SERVER_PORT);

                while (isGroupOwner && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        // added
                        clientExecutor.submit(() -> handleClient(client));

                    } catch (SocketException se) {
                        Log.d(TAG, "Server socket closed");
                        break;
                    } catch (IOException | RuntimeException e) {
                        Log.e(TAG, "Server error", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server setup  error", e);
            } finally {
                closeServerSocket();
                // added
                if (clientExecutor != null) {
                    clientExecutor.shutdown();
                }
            }
        }).start();
    }
    // added
    private void handleClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String json = in.readLine();
            AttendancePayload p = new Gson().fromJson(json, AttendancePayload.class);
            // added
            if (p == null || p.email == null) {
                Log.e(TAG, "Invalid client data received");
                return;
            }
            if (studentEmails.contains(p.email)) {
                float dist = calculateDistance(p.lat, p.lon);
                AttendanceRecord rec = new AttendanceRecord(p.name, p.deviceId, p.email, p.timestamp, dist);
                synchronized (cycleCache) {
                    cycleCache.add(rec);
                }
                receivedCount++;
                Log.d(TAG, "Received record " + receivedCount + " of " + expectedBatchCount);
                if (receivedCount == expectedBatchCount) {
                    cycleHandler.removeCallbacks(finishRunnable);
                    finishCycle(new ArrayList<>());
                }
            } else {
                Log.d(TAG, "Student not registered");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Student not registered", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }
    private synchronized void closeServerSocket() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                Log.d(TAG, "ServerSocket closed");
            } catch (IOException ignored) {
            }
            serverSocket = null;
            Log.d(TAG, "ServerSocket closed");
        }
    }
    private void stopHosting() {
        isHost = false;
        isGroupOwner = false;
        // added
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Local services cleared");
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed to clear local services: " + reason);
            }
        });
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                closeServerSocket();
                Toast.makeText(MainActivity.this,
                        "Stopped hosting", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                closeServerSocket();
                Log.e(TAG, "removeGroup failed: " + reason);
            }
        });
    }
    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (!info.groupFormed || info.isGroupOwner) return;

        runClient(info.groupOwnerAddress.getHostAddress());
    }
    private void runClient(String hostAddr) {
        new Thread(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(hostAddr, SERVER_PORT), 5000);
                s.getOutputStream().write(prepareAttendanceJson().getBytes());
                Log.d(TAG, "Attendance data sent successfully");

            } catch (Exception e) {
                Log.e(TAG, "Client error", e);
//                e.printStackTrace();
            } finally {
                manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Disconnected from group");
                    }

                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "Failed to disconnect from group: " + reason);
                    }
                });
            }
        }).start();
    }
    @SuppressLint("HardwareIds")
    private String prepareAttendanceJson() {
        String name = android.os.Build.MODEL;
        String deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        //added
        String email = signedInAccount != null ? signedInAccount.getEmail() : "unknown@example.com";
        long ts = System.currentTimeMillis();

        double lat = 0, lon = 0;
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc != null) {
                lat = loc.getLatitude();
                lon = loc.getLongitude();
            }
        }

        AttendancePayload p = new AttendancePayload(name, deviceId, email, ts, lat, lon);
        return new Gson().toJson(p);
    }
    private float calculateDistance(double lat, double lon) {
        if (hostLocation == null) return -1f;
        float[] r = new float[1];
        android.location.Location.distanceBetween(
                hostLocation.getLatitude(),
                hostLocation.getLongitude(),
                lat, lon, r);
        return r[0];
    }

    private static class AttendancePayload {
        String name, deviceId, email;
        long timestamp;
        double lat, lon;

        AttendancePayload(String n, String d, String e, long t, double la, double lo) {
            name = n;
            deviceId = d;
            email = e;
            timestamp = t;
            lat = la;
            lon = lo;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN_HOST) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                signedInAccount = task.getResult(ApiException.class);
                fetchTeacherCourses();
            } catch (ApiException e) {
                Log.e(TAG, "Host sign-in failed", e);
                Toast.makeText(this, "Host sign-in failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == RC_SIGN_IN_CLIENT) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                signedInAccount = task.getResult(ApiException.class);
                discoverServices();
            } catch (ApiException e) {
                Log.e(TAG, "Client sign-in failed", e);
                Toast.makeText(this, "Client sign-in failed: " + e.getStatusCode(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void fetchTeacherCourses() {
        new Thread(() -> {
            int retries = 0;
            while (retries < MAX_RETRIES) {
                try {
                    GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                            this, Collections.singleton("https://www.googleapis.com/auth/classroom.courses.readonly"));
                    credential.setSelectedAccount(signedInAccount.getAccount());

                    Classroom service = new Classroom.Builder(
                            AndroidHttp.newCompatibleTransport(),
                            JacksonFactory.getDefaultInstance(),
                            credential)
                            .setApplicationName("Presence")
                            .build();

                    List<Course> courses = service.courses().list().execute().getCourses();
                    teacherCourses.clear();
                    if (courses != null) {
                        teacherCourses.addAll(courses);
                    }
                    if (teacherCourses.isEmpty()) {
                        runOnUiThread(() -> Toast.makeText(this, "No courses found", Toast.LENGTH_SHORT).show());
                    } else {
                        List<String> courseNames = new ArrayList<>();
                        for (Course course : teacherCourses) {
                            courseNames.add(course.getName());
                        }
                        runOnUiThread(() -> showCourseSelectionDialog(courseNames));
                    }
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to fetch courses", e);
                    retries++;
                    if (retries >= MAX_RETRIES) {
                        runOnUiThread(() -> Toast.makeText(this, "Failed to fetch Classroom data", Toast.LENGTH_SHORT).show());
                    } else {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
//                runOnUiThread(() -> Toast.makeText(this, "Error fetching courses", Toast.LENGTH_SHORT).show());
                }
            }

        }).start();
    }

    private void showCourseSelectionDialog(List<String> courseNames) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select a Course");
        builder.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, courseNames), (dialog, which) -> {
            String selectedCourseId = teacherCourses.get(which).getId();
            fetchStudentList(selectedCourseId);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
    private void fetchStudentList(String courseId) {
        new Thread(() -> {
            int retries = 0;
            while (retries < MAX_RETRIES) {
                try {
                    GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                            this, Collections.singleton("https://www.googleapis.com/auth/classroom.rosters.readonly"));
                    credential.setSelectedAccount(signedInAccount.getAccount());

                    Classroom service = new Classroom.Builder(
                            AndroidHttp.newCompatibleTransport(),
                            JacksonFactory.getDefaultInstance(),
                            credential)
                            .setApplicationName("Presence")
                            .build();

                    List<Student> students = service.courses().students().list(courseId).execute().getStudents();
                    studentEmails.clear();
                    if (students != null) {
                        for (Student student : students) {
                            studentEmails.add(student.getProfile().getEmailAddress());
                        }
                    }
                    Log.d(TAG, "Fetched " + studentEmails.size() + " students from Classroom");
                    runOnUiThread(() -> resetAndBecomeGroupOwner());
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to fetch student list", e);
                    retries++;
                    if (retries >= MAX_RETRIES) {
                        runOnUiThread(() -> Toast.makeText(this, "Failed to fetch Classroom data", Toast.LENGTH_SHORT).show());
                    }else {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }).start();
    }
}

