package com.videop2p.webrtc;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

public class CallActivity extends AppCompatActivity implements
        WebRTCManager.WebRTCListener, SignalingManager.SignalingListener {

    private static final String TAG = "CallActivity";

    private SurfaceViewRenderer svrLocal;
    private SurfaceViewRenderer svrRemote;
    private ImageButton btnMute;
    private ImageButton btnSwitchCamera;
    private ImageButton btnEndCall;
    private TextView tvCallStatus;

    private WebRTCManager webRTCManager;
    private SignalingManager signalingManager;
    
    private boolean isAudioMuted = false;
    private boolean isVideoEnabled = true;
    private boolean isGroupOwner = false;
    private String deviceName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        isGroupOwner = getIntent().getBooleanExtra("isGroupOwner", false);
        deviceName = getIntent().getStringExtra("deviceName");
        
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = "Android Device";
        }

        Log.d(TAG, "onCreate, isGroupOwner: " + isGroupOwner);

        initViews();
        initWebRTC();
        initSignaling();
    }

    private void initViews() {
        svrLocal = findViewById(R.id.svrLocal);
        svrRemote = findViewById(R.id.svrRemote);
        btnMute = findViewById(R.id.btnMute);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnEndCall = findViewById(R.id.btnEndCall);
        tvCallStatus = findViewById(R.id.tvCallStatus);

        btnMute.setOnClickListener(v -> toggleMute());
        btnSwitchCamera.setOnClickListener(v -> switchCamera());
        btnEndCall.setOnClickListener(v -> endCall());
    }

    private void initWebRTC() {
        webRTCManager = new WebRTCManager(this, this);
        webRTCManager.setLocalRenderer(svrLocal);
        webRTCManager.setRemoteRenderer(svrRemote);
        webRTCManager.startLocalVideo();
        webRTCManager.createPeerConnection();
    }

    private void initSignaling() {
        signalingManager = new SignalingManager(this, this);
        signalingManager.register();

        if (!isGroupOwner) {
            tvCallStatus.setText("Creating offer...");
            tvCallStatus.setVisibility(View.VISIBLE);
            webRTCManager.createOffer();
        } else {
            tvCallStatus.setText("Waiting for incoming call...");
            tvCallStatus.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onLocalSessionDescriptionCreated(SessionDescription sdp) {
        Log.d(TAG, "Local session description created: " + sdp.type);
        
        if (sdp.type == SessionDescription.Type.OFFER) {
            tvCallStatus.setText("Sending offer...");
            signalingManager.sendOffer(sdp);
        } else if (sdp.type == SessionDescription.Type.ANSWER) {
            tvCallStatus.setText("Sending answer...");
            signalingManager.sendAnswer(sdp);
        }
    }

    @Override
    public void onRemoteSessionDescriptionSet() {
        Log.d(TAG, "Remote session description set");
        tvCallStatus.setText("Connection established...");
        
        if (isGroupOwner) {
            webRTCManager.createAnswer();
        }
    }

    @Override
    public void onIceCandidateGenerated(IceCandidate candidate) {
        Log.d(TAG, "ICE candidate generated: " + candidate.sdp);
        if (signalingManager != null) {
            signalingManager.sendIceCandidate(candidate);
        }
    }

    @Override
    public void onIceCandidatesReady() {
        Log.d(TAG, "ICE candidates ready");
    }

    @Override
    public void onConnectionStateChange(PeerConnection.PeerConnectionState state) {
        Log.d(TAG, "Connection state: " + state);
        
        runOnUiThread(() -> {
            switch (state) {
                case CONNECTED:
                    tvCallStatus.setVisibility(View.GONE);
                    Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show();
                    break;
                case CONNECTING:
                    tvCallStatus.setText("Connecting...");
                    tvCallStatus.setVisibility(View.VISIBLE);
                    break;
                case DISCONNECTED:
                    tvCallStatus.setText("Disconnected");
                    tvCallStatus.setVisibility(View.VISIBLE);
                    break;
                case FAILED:
                    tvCallStatus.setText("Connection failed");
                    tvCallStatus.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show();
                    break;
                case CLOSED:
                    finish();
                    break;
            }
        });
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "Error: " + error);
        runOnUiThread(() -> {
            tvCallStatus.setText("Error: " + error);
            tvCallStatus.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Error: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDeviceDiscovered(java.util.List<android.net.wifi.p2p.WifiP2pDevice> devices) {}

    @Override
    public void onConnectionSuccess(boolean isGroupOwner, String groupOwnerAddress) {}

    @Override
    public void onConnectionFailed(String error) {
        runOnUiThread(() -> {
            tvCallStatus.setText("Connection failed: " + error);
            tvCallStatus.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Connection failed: " + error, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onOfferReceived(SessionDescription offer) {
        Log.d(TAG, "Offer received");
        runOnUiThread(() -> {
            tvCallStatus.setText("Received offer, processing...");
            webRTCManager.setRemoteSessionDescription(offer);
        });
    }

    @Override
    public void onAnswerReceived(SessionDescription answer) {
        Log.d(TAG, "Answer received");
        runOnUiThread(() -> {
            tvCallStatus.setText("Received answer, processing...");
            webRTCManager.setRemoteSessionDescription(answer);
        });
    }

    @Override
    public void onIceCandidateReceived(IceCandidate candidate) {
        Log.d(TAG, "ICE candidate received: " + candidate.sdp);
        webRTCManager.addRemoteIceCandidate(candidate);
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(() -> {
            tvCallStatus.setText("Disconnected");
            tvCallStatus.setVisibility(View.VISIBLE);
            endCall();
        });
    }

    @Override
    public void onMessageReceived(String message) {}

    private void toggleMute() {
        isAudioMuted = !isAudioMuted;
        if (webRTCManager != null) {
            webRTCManager.toggleAudio(!isAudioMuted);
        }
        
        if (isAudioMuted) {
            btnMute.setImageResource(R.drawable.ic_mic_off);
            Toast.makeText(this, "Muted", Toast.LENGTH_SHORT).show();
        } else {
            btnMute.setImageResource(R.drawable.ic_mic);
            Toast.makeText(this, "Unmuted", Toast.LENGTH_SHORT).show();
        }
    }

    private void switchCamera() {
        if (webRTCManager != null) {
            webRTCManager.switchCamera();
            Toast.makeText(this, "Camera switched", Toast.LENGTH_SHORT).show();
        }
    }

    private void endCall() {
        if (webRTCManager != null) {
            webRTCManager.close();
        }
        if (signalingManager != null) {
            signalingManager.close();
        }
        finish();
    }

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
        if (webRTCManager != null) {
            webRTCManager.close();
        }
        if (signalingManager != null) {
            signalingManager.close();
        }
    }

    @Override
    public void onBackPressed() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("End Call")
                .setMessage("Are you sure you want to end the call?")
                .setPositiveButton("End Call", (dialog, which) -> {
                    endCall();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
