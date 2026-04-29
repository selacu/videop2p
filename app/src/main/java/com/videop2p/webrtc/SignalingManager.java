package com.videop2p.webrtc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SignalingManager {
    private static final String TAG = "SignalingManager";
    private static final int PORT = 8888;
    private static final int SOCKET_TIMEOUT = 10000;

    private Context context;
    private SignalingListener listener;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver broadcastReceiver;
    private IntentFilter intentFilter;

    private List<WifiP2pDevice> peers = new ArrayList<>();
    private String deviceName;
    private boolean isConnected = false;
    private boolean isGroupOwner = false;

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private BufferedReader input;
    private OutputStream output;
    private ExecutorService executorService;
    private Handler mainHandler;

    public interface SignalingListener {
        void onDeviceDiscovered(List<WifiP2pDevice> devices);
        void onConnectionSuccess(boolean isGroupOwner, String groupOwnerAddress);
        void onConnectionFailed(String error);
        void onOfferReceived(SessionDescription offer);
        void onAnswerReceived(SessionDescription answer);
        void onIceCandidateReceived(IceCandidate candidate);
        void onDisconnected();
        void onMessageReceived(String message);
    }

    public SignalingManager(Context context, SignalingListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initWifiP2P();
    }

    private void initWifiP2P() {
        wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = wifiP2pManager.initialize(context, context.getMainLooper(), null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Broadcast received: " + action);

                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.d(TAG, "WiFi P2P is enabled");
                    } else {
                        Log.d(TAG, "WiFi P2P is disabled");
                    }
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    if (wifiP2pManager != null) {
                        wifiP2pManager.requestPeers(channel, peerListListener);
                    }
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    if (wifiP2pManager != null) {
                        wifiP2pManager.requestConnectionInfo(channel, connectionListener);
                    }
                } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                    WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    if (device != null) {
                        deviceName = device.deviceName;
                        Log.d(TAG, "Device name: " + deviceName);
                    }
                }
            }
        };
    }

    public void register() {
        context.registerReceiver(broadcastReceiver, intentFilter);
    }

    public void unregister() {
        context.unregisterReceiver(broadcastReceiver);
    }

    public void discoverPeers() {
        Log.d(TAG, "Starting peer discovery");
        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Peer discovery initiated");
            }

            @Override
            public void onFailure(int reasonCode) {
                Log.e(TAG, "Peer discovery failed: " + reasonCode);
                notifyOnConnectionFailed("Peer discovery failed");
            }
        });
    }

    private WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {
            List<WifiP2pDevice> freshPeers = new ArrayList<>(peerList.getDeviceList());
            Log.d(TAG, "Peers discovered: " + freshPeers.size());
            
            peers.clear();
            peers.addAll(freshPeers);
            
            if (listener != null) {
                listener.onDeviceDiscovered(freshPeers);
            }
        }
    };

    public void connectToDevice(WifiP2pDevice device) {
        Log.d(TAG, "Connecting to: " + device.deviceName);
        
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        
        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Connection initiated");
            }

            @Override
            public void onFailure(int reasonCode) {
                Log.e(TAG, "Connection failed: " + reasonCode);
                notifyOnConnectionFailed("Connection failed, code: " + reasonCode);
            }
        });
    }

    private WifiP2pManager.ConnectionInfoListener connectionListener = new WifiP2pManager.ConnectionInfoListener() {
        @Override
        public void onConnectionInfoAvailable(WifiP2pInfo info) {
            Log.d(TAG, "Connection info available, group formed: " + info.groupFormed);
            
            if (info.groupFormed) {
                isConnected = true;
                isGroupOwner = info.isGroupOwner;
                String groupOwnerAddress = info.groupOwnerAddress.getHostAddress();
                Log.d(TAG, "Is group owner: " + isGroupOwner);
                Log.d(TAG, "Group owner address: " + groupOwnerAddress);

                if (listener != null) {
                    listener.onConnectionSuccess(isGroupOwner, groupOwnerAddress);
                }

                startSocketConnection(isGroupOwner, groupOwnerAddress);
            } else {
                isConnected = false;
                if (listener != null) {
                    listener.onDisconnected();
                }
            }
        }
    };

    private void startSocketConnection(boolean isGroupOwner, String groupOwnerAddress) {
        executorService.execute(() -> {
            try {
                if (isGroupOwner) {
                    Log.d(TAG, "Starting server socket");
                    serverSocket = new ServerSocket(PORT);
                    Log.d(TAG, "Server socket created, waiting for client...");
                    clientSocket = serverSocket.accept();
                    Log.d(TAG, "Client connected");
                } else {
                    Log.d(TAG, "Connecting to server: " + groupOwnerAddress);
                    clientSocket = new Socket();
                    clientSocket.bind(null);
                    clientSocket.connect(new InetSocketAddress(groupOwnerAddress, PORT), SOCKET_TIMEOUT);
                    Log.d(TAG, "Connected to server");
                }

                input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                output = clientSocket.getOutputStream();

                startMessageListener();

            } catch (SocketTimeoutException e) {
                Log.e(TAG, "Socket connection timeout: " + e.getMessage());
                notifyOnConnectionFailed("Connection timeout: " + e.getMessage());
            } catch (IOException e) {
                Log.e(TAG, "Socket error: " + e.getMessage());
                notifyOnConnectionFailed("Socket error: " + e.getMessage());
            }
        });
    }

    private void startMessageListener() {
        executorService.execute(() -> {
            try {
                String message;
                while (!Thread.currentThread().isInterrupted() && (message = input.readLine()) != null) {
                    Log.d(TAG, "Message received: " + message);
                    processMessage(message);
                }
            } catch (IOException e) {
                Log.e(TAG, "Message listener error: " + e.getMessage());
            }
        });
    }

    private void processMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.getString("type");

            switch (type) {
                case "offer":
                    SessionDescription offer = new SessionDescription(
                            SessionDescription.Type.OFFER,
                            json.getString("sdp")
                    );
                    notifyOnOfferReceived(offer);
                    break;

                case "answer":
                    SessionDescription answer = new SessionDescription(
                            SessionDescription.Type.ANSWER,
                            json.getString("sdp")
                    );
                    notifyOnAnswerReceived(answer);
                    break;

                case "candidate":
                    IceCandidate candidate = new IceCandidate(
                            json.getString("id"),
                            json.getInt("label"),
                            json.getString("candidate")
                    );
                    notifyOnIceCandidateReceived(candidate);
                    break;

                default:
                    Log.d(TAG, "Unknown message type: " + type);
                    notifyOnMessageReceived(message);
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "JSON parse error: " + e.getMessage());
        }
    }

    public void sendOffer(SessionDescription offer) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "offer");
            json.put("sdp", offer.description);
            sendMessage(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating offer JSON: " + e.getMessage());
        }
    }

    public void sendAnswer(SessionDescription answer) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "answer");
            json.put("sdp", answer.description);
            sendMessage(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating answer JSON: " + e.getMessage());
        }
    }

    public void sendIceCandidate(IceCandidate candidate) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "candidate");
            json.put("id", candidate.sdpMid);
            json.put("label", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);
            sendMessage(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating candidate JSON: " + e.getMessage());
        }
    }

    public void sendIceCandidates(List<IceCandidate> candidates) {
        for (IceCandidate candidate : candidates) {
            sendIceCandidate(candidate);
        }
    }

    private void sendMessage(String message) {
        executorService.execute(() -> {
            try {
                if (output != null) {
                    Log.d(TAG, "Sending message: " + message);
                    output.write((message + "\n").getBytes());
                    output.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error sending message: " + e.getMessage());
            }
        });
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isGroupOwner() {
        return isGroupOwner;
    }

    public void disconnect() {
        executorService.execute(() -> {
            try {
                if (input != null) {
                    input.close();
                    input = null;
                }
                if (output != null) {
                    output.close();
                    output = null;
                }
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                    clientSocket = null;
                }
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                    serverSocket = null;
                }
            } catch (IOException e) {
                Log.e(TAG, "Error disconnecting: " + e.getMessage());
            }
        });

        if (wifiP2pManager != null && channel != null) {
            wifiP2pManager.cancelConnect(channel, null);
            wifiP2pManager.removeGroup(channel, null);
        }

        isConnected = false;
    }

    private void notifyOnConnectionFailed(final String error) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionFailed(error);
            }
        });
    }

    private void notifyOnOfferReceived(final SessionDescription offer) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onOfferReceived(offer);
            }
        });
    }

    private void notifyOnAnswerReceived(final SessionDescription answer) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onAnswerReceived(answer);
            }
        });
    }

    private void notifyOnIceCandidateReceived(final IceCandidate candidate) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onIceCandidateReceived(candidate);
            }
        });
    }

    private void notifyOnMessageReceived(final String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onMessageReceived(message);
            }
        });
    }

    public void close() {
        disconnect();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
