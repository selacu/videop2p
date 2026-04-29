package com.videop2p.webrtc;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class WebRTCManager {
    private static final String TAG = "WebRTCManager";
    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";
    private static final String LOCAL_STREAM_LABEL = "ARDAMS";
    private static final int VIDEO_RESOLUTION_WIDTH = 1280;
    private static final int VIDEO_RESOLUTION_HEIGHT = 720;
    private static final int VIDEO_FPS = 30;

    private Context context;
    private EglBase rootEglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private MediaStream localMediaStream;
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;
    private boolean isVideoEnabled = true;
    private boolean isAudioEnabled = true;
    private boolean isFrontCamera = true;

    private SignalingManager signalingManager;
    private WebRTCListener webRTCListener;
    private ScheduledExecutorService executorService;

    private List<IceCandidate> localIceCandidates = new ArrayList<>();
    private SessionDescription localSessionDescription;

    public interface WebRTCListener {
        void onLocalSessionDescriptionCreated(SessionDescription sdp);
        void onRemoteSessionDescriptionSet();
        void onIceCandidateGenerated(IceCandidate candidate);
        void onIceCandidatesReady();
        void onConnectionStateChange(PeerConnection.PeerConnectionState state);
        void onError(String error);
    }

    public WebRTCManager(Context context, WebRTCListener listener) {
        this.context = context.getApplicationContext();
        this.webRTCListener = listener;
        this.executorService = Executors.newSingleThreadScheduledExecutor();
        initPeerConnectionFactory();
    }

    private void initPeerConnectionFactory() {
        executorService.execute(() -> {
            PeerConnectionFactory.InitializationOptions initializationOptions =
                    PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(true)
                            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(initializationOptions);

            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.disableEncryption = false;
            options.disableNetworkMonitor = false;

            rootEglBase = EglBase.create();

            VideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                    rootEglBase.getEglBaseContext(), true, true);
            VideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                    rootEglBase.getEglBaseContext());

            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .setAudioDeviceModule(JavaAudioDeviceModule.builder(context)
                            .setSamplesReadyCallback(null)
                            .setUseHardwareAcousticEchoCanceler(true)
                            .setUseHardwareNoiseSuppressor(true)
                            .createAudioDeviceModule())
                    .createPeerConnectionFactory();
        });
    }

    public void setLocalRenderer(SurfaceViewRenderer renderer) {
        this.localRenderer = renderer;
        if (localRenderer != null) {
            localRenderer.init(rootEglBase.getEglBaseContext(), null);
            localRenderer.setMirror(true);
            localRenderer.setEnableHardwareScaler(true);
        }
    }

    public void setRemoteRenderer(SurfaceViewRenderer renderer) {
        this.remoteRenderer = renderer;
        if (remoteRenderer != null) {
            remoteRenderer.init(rootEglBase.getEglBaseContext(), null);
            remoteRenderer.setMirror(false);
            remoteRenderer.setEnableHardwareScaler(true);
        }
    }

    public void startLocalVideo() {
        executorService.execute(() -> {
            if (videoCapturer == null) {
                videoCapturer = createVideoCapturer();
            }
            if (videoCapturer == null) {
                Log.e(TAG, "Failed to create video capturer");
                return;
            }

            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread", rootEglBase.getEglBaseContext());

            videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
            videoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS);

            localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
            localVideoTrack.setEnabled(isVideoEnabled);

            if (localRenderer != null && localVideoTrack != null) {
                localVideoTrack.addSink(localRenderer);
            }

            audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
            localAudioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
            localAudioTrack.setEnabled(isAudioEnabled);

            localMediaStream = peerConnectionFactory.createLocalMediaStream(LOCAL_STREAM_LABEL);
            localMediaStream.addTrack(localVideoTrack);
            localMediaStream.addTrack(localAudioTrack);
        });
    }

    private VideoCapturer createVideoCapturer() {
        CameraEnumerator cameraEnumerator = new Camera2Enumerator(context);
        String[] deviceNames = cameraEnumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            if (isFrontCamera ? cameraEnumerator.isFrontFacing(deviceName) : 
                cameraEnumerator.isBackFacing(deviceName)) {
                return cameraEnumerator.createCapturer(deviceName, null);
            }
        }

        if (deviceNames.length > 0) {
            return cameraEnumerator.createCapturer(deviceNames[0], null);
        }

        return null;
    }

    public void switchCamera() {
        executorService.execute(() -> {
            if (videoCapturer == null) return;
            try {
                videoCapturer.switchCamera(null);
                isFrontCamera = !isFrontCamera;
            } catch (Exception e) {
                Log.e(TAG, "Failed to switch camera: " + e.getMessage());
            }
        });
    }

    public void toggleVideo(boolean enabled) {
        isVideoEnabled = enabled;
        if (localVideoTrack != null) {
            localVideoTrack.setEnabled(enabled);
        }
    }

    public void toggleAudio(boolean enabled) {
        isAudioEnabled = enabled;
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(enabled);
        }
    }

    public void createPeerConnection() {
        executorService.execute(() -> {
            if (peerConnectionFactory == null) {
                Log.e(TAG, "PeerConnectionFactory is null");
                return;
            }

            List<PeerConnection.IceServer> iceServers = new ArrayList<>();
            iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

            PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
            rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
            rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
            rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
            rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
            rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

            peerConnection = peerConnectionFactory.createPeerConnection(
                    rtcConfig, new PeerConnectionObserver());

            if (peerConnection == null) {
                Log.e(TAG, "Failed to create peer connection");
                return;
            }

            if (localMediaStream != null) {
                for (AudioTrack audioTrack : localMediaStream.audioTracks) {
                    peerConnection.addTrack(audioTrack, localMediaStream.getId());
                }
                for (VideoTrack videoTrack : localMediaStream.videoTracks) {
                    peerConnection.addTrack(videoTrack, localMediaStream.getId());
                }
            }
        });
    }

    public void createOffer() {
        executorService.execute(() -> {
            if (peerConnection == null) {
                Log.e(TAG, "PeerConnection is null");
                return;
            }

            MediaConstraints sdpConstraints = new MediaConstraints();
            sdpConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            sdpConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

            peerConnection.createOffer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    Log.d(TAG, "Create offer success: " + sdp.type);
                    localSessionDescription = sdp;
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sessionDescription) {}
                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "Set local description success");
                            if (webRTCListener != null) {
                                webRTCListener.onLocalSessionDescriptionCreated(sdp);
                            }
                        }
                        @Override
                        public void onCreateFailure(String s) {
                            Log.e(TAG, "Set local description create failure: " + s);
                        }
                        @Override
                        public void onSetFailure(String s) {
                            Log.e(TAG, "Set local description set failure: " + s);
                            if (webRTCListener != null) {
                                webRTCListener.onError("Failed to set local description: " + s);
                            }
                        }
                    }, sdp);
                }

                @Override
                public void onSetSuccess() {}
                @Override
                public void onCreateFailure(String s) {
                    Log.e(TAG, "Create offer failure: " + s);
                    if (webRTCListener != null) {
                        webRTCListener.onError("Failed to create offer: " + s);
                    }
                }
                @Override
                public void onSetFailure(String s) {
                    Log.e(TAG, "Create offer set failure: " + s);
                }
            }, sdpConstraints);
        });
    }

    public void createAnswer() {
        executorService.execute(() -> {
            if (peerConnection == null) {
                Log.e(TAG, "PeerConnection is null");
                return;
            }

            MediaConstraints sdpConstraints = new MediaConstraints();
            sdpConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            sdpConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

            peerConnection.createAnswer(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    Log.d(TAG, "Create answer success: " + sdp.type);
                    localSessionDescription = sdp;
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sessionDescription) {}
                        @Override
                        public void onSetSuccess() {
                            Log.d(TAG, "Set local description success (answer)");
                            if (webRTCListener != null) {
                                webRTCListener.onLocalSessionDescriptionCreated(sdp);
                            }
                        }
                        @Override
                        public void onCreateFailure(String s) {
                            Log.e(TAG, "Set local description create failure (answer): " + s);
                        }
                        @Override
                        public void onSetFailure(String s) {
                            Log.e(TAG, "Set local description set failure (answer): " + s);
                            if (webRTCListener != null) {
                                webRTCListener.onError("Failed to set local description: " + s);
                            }
                        }
                    }, sdp);
                }

                @Override
                public void onSetSuccess() {}
                @Override
                public void onCreateFailure(String s) {
                    Log.e(TAG, "Create answer failure: " + s);
                    if (webRTCListener != null) {
                        webRTCListener.onError("Failed to create answer: " + s);
                    }
                }
                @Override
                public void onSetFailure(String s) {
                    Log.e(TAG, "Create answer set failure: " + s);
                }
            }, sdpConstraints);
        });
    }

    public void setRemoteSessionDescription(SessionDescription sdp) {
        executorService.execute(() -> {
            if (peerConnection == null) {
                Log.e(TAG, "PeerConnection is null");
                return;
            }

            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {}
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Set remote description success");
                    if (webRTCListener != null) {
                        webRTCListener.onRemoteSessionDescriptionSet();
                    }
                }
                @Override
                public void onCreateFailure(String s) {
                    Log.e(TAG, "Set remote description create failure: " + s);
                }
                @Override
                public void onSetFailure(String s) {
                    Log.e(TAG, "Set remote description set failure: " + s);
                    if (webRTCListener != null) {
                        webRTCListener.onError("Failed to set remote description: " + s);
                    }
                }
            }, sdp);
        });
    }

    public void addRemoteIceCandidate(IceCandidate candidate) {
        executorService.execute(() -> {
            if (peerConnection != null) {
                Log.d(TAG, "Adding remote ice candidate: " + candidate.sdp);
                peerConnection.addIceCandidate(candidate);
            }
        });
    }

    public List<IceCandidate> getLocalIceCandidates() {
        return localIceCandidates;
    }

    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "Signaling state change: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "ICE connection state change: " + iceConnectionState);
        }

        @Override
        public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.d(TAG, "Standardized ICE connection state change: " + newState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Log.d(TAG, "ICE connection receiving change: " + b);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "ICE gathering state change: " + iceGatheringState);
            if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                if (webRTCListener != null) {
                    webRTCListener.onIceCandidatesReady();
                }
            }
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "ICE candidate generated: " + iceCandidate.sdp);
            localIceCandidates.add(iceCandidate);
            if (webRTCListener != null) {
                webRTCListener.onIceCandidateGenerated(iceCandidate);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "ICE candidates removed");
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "Stream added: " + mediaStream.getId());
            if (mediaStream.videoTracks.size() > 0) {
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                if (remoteRenderer != null && remoteVideoTrack != null) {
                    remoteVideoTrack.addSink(remoteRenderer);
                }
            }
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "Stream removed: " + mediaStream.getId());
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "Data channel: " + dataChannel.label());
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed");
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            Log.d(TAG, "Track added");
        }

        @Override
        public void onTrack(RtpTransceiver rtpTransceiver) {
            Log.d(TAG, "Track: " + rtpTransceiver.getReceiver().track().kind());
            if (rtpTransceiver.getReceiver().track() instanceof VideoTrack) {
                VideoTrack remoteVideoTrack = (VideoTrack) rtpTransceiver.getReceiver().track();
                if (remoteRenderer != null && remoteVideoTrack != null) {
                    remoteVideoTrack.addSink(remoteRenderer);
                }
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
            Log.d(TAG, "Connection state change: " + newState);
            if (webRTCListener != null) {
                webRTCListener.onConnectionStateChange(newState);
            }
        }
    }

    private abstract class SdpObserver implements org.webrtc.SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override
        public void onSetSuccess() {}
        @Override
        public void onCreateFailure(String s) {}
        @Override
        public void onSetFailure(String s) {}
    }

    public void close() {
        executorService.execute(() -> {
            if (videoCapturer != null) {
                try {
                    videoCapturer.stopCapture();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                videoCapturer.dispose();
                videoCapturer = null;
            }

            if (videoSource != null) {
                videoSource.dispose();
                videoSource = null;
            }

            if (audioSource != null) {
                audioSource.dispose();
                audioSource = null;
            }

            if (peerConnection != null) {
                peerConnection.close();
                peerConnection.dispose();
                peerConnection = null;
            }

            if (localRenderer != null) {
                localRenderer.release();
                localRenderer = null;
            }

            if (remoteRenderer != null) {
                remoteRenderer.release();
                remoteRenderer = null;
            }

            if (rootEglBase != null) {
                rootEglBase.release();
                rootEglBase = null;
            }

            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
                peerConnectionFactory = null;
            }

            PeerConnectionFactory.stopInternalTracingCapture();
            PeerConnectionFactory.shutdown();
        });

        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
