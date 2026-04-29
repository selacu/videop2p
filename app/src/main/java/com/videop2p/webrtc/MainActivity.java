package com.videop2p.webrtc;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements 
        SignalingManager.SignalingListener, DeviceAdapter.OnDeviceClickListener {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    private TextView tvStatus;
    private EditText etDeviceName;
    private Button btnSearch;
    private RecyclerView rvDevices;
    private TextView tvDevicesLabel;
    private ProgressBar progressBar;

    private SignalingManager signalingManager;
    private DeviceAdapter deviceAdapter;
    private List<WifiP2pDevice> deviceList = new ArrayList<>();
    private boolean isPermissionsGranted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        etDeviceName = findViewById(R.id.etDeviceName);
        btnSearch = findViewById(R.id.btnSearch);
        rvDevices = findViewById(R.id.rvDevices);
        tvDevicesLabel = findViewById(R.id.tvDevicesLabel);
        progressBar = findViewById(R.id.progressBar);

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        deviceAdapter = new DeviceAdapter(deviceList, this);
        rvDevices.setAdapter(deviceAdapter);

        btnSearch.setOnClickListener(v -> {
            if (!isPermissionsGranted) {
                checkPermissions();
                return;
            }
            startDiscovery();
        });
    }

    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, 
                    permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            isPermissionsGranted = true;
            initSignalingManager();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            isPermissionsGranted = allGranted;
            if (allGranted) {
                initSignalingManager();
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                showPermissionRationale();
            }
        }
    }

    private void showPermissionRationale() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("This app requires camera, microphone, and location permissions " +
                        "to enable video calls. Please grant all permissions in settings.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void initSignalingManager() {
        signalingManager = new SignalingManager(this, this);
        signalingManager.register();
    }

    private void startDiscovery() {
        if (signalingManager == null) {
            Toast.makeText(this, "Signaling manager not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        tvDevicesLabel.setVisibility(View.GONE);
        rvDevices.setVisibility(View.GONE);
        tvStatus.setText(R.string.searching);
        btnSearch.setEnabled(false);

        deviceList.clear();
        deviceAdapter.updateDevices(deviceList);

        signalingManager.discoverPeers();
        
        new android.os.Handler().postDelayed(() -> {
            progressBar.setVisibility(View.GONE);
            btnSearch.setEnabled(true);
            if (deviceList.isEmpty()) {
                tvStatus.setText(R.string.no_devices_found);
            }
        }, 10000);
    }

    @Override
    public void onDeviceDiscovered(List<WifiP2pDevice> devices) {
        Log.d(TAG, "Devices discovered: " + devices.size());
        
        deviceList.clear();
        deviceList.addAll(devices);
        deviceAdapter.updateDevices(devices);

        if (!devices.isEmpty()) {
            tvDevicesLabel.setVisibility(View.VISIBLE);
            rvDevices.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            tvStatus.setText(getString(R.string.devices_found, devices.size()));
        } else {
            tvDevicesLabel.setVisibility(View.GONE);
            rvDevices.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDeviceClick(WifiP2pDevice device) {
        Log.d(TAG, "Clicked on device: " + device.deviceName);
        
        new AlertDialog.Builder(this)
                .setTitle("Connect to Device")
                .setMessage("Do you want to call " + device.deviceName + "?")
                .setPositiveButton("Call", (dialog, which) -> {
                    connectToDevice(device);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectToDevice(WifiP2pDevice device) {
        if (signalingManager == null) {
            return;
        }
        
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Connecting to " + device.deviceName + "...");
        signalingManager.connectToDevice(device);
    }

    @Override
    public void onConnectionSuccess(boolean isGroupOwner, String groupOwnerAddress) {
        Log.d(TAG, "Connection success, isGroupOwner: " + isGroupOwner);
        progressBar.setVisibility(View.GONE);
        
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra("isGroupOwner", isGroupOwner);
        intent.putExtra("deviceName", etDeviceName.getText().toString().trim());
        startActivity(intent);
    }

    @Override
    public void onConnectionFailed(String error) {
        Log.e(TAG, "Connection failed: " + error);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Connection failed: " + error);
        Toast.makeText(this, "Connection failed: " + error, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onOfferReceived(org.webrtc.SessionDescription offer) {}

    @Override
    public void onAnswerReceived(org.webrtc.SessionDescription answer) {}

    @Override
    public void onIceCandidateReceived(org.webrtc.IceCandidate candidate) {}

    @Override
    public void onDisconnected() {
        tvStatus.setText(R.string.disconnected);
    }

    @Override
    public void onMessageReceived(String message) {}

    @Override
    protected void onResume() {
        super.onResume();
        if (signalingManager != null) {
            signalingManager.register();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (signalingManager != null) {
            signalingManager.unregister();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (signalingManager != null) {
            signalingManager.close();
        }
    }
}
