package com.videop2p.webrtc;

import android.net.wifi.p2p.WifiP2pDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    private List<WifiP2pDevice> devices;
    private OnDeviceClickListener listener;

    public interface OnDeviceClickListener {
        void onDeviceClick(WifiP2pDevice device);
    }

    public DeviceAdapter(List<WifiP2pDevice> devices, OnDeviceClickListener listener) {
        this.devices = devices;
        this.listener = listener;
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        WifiP2pDevice device = devices.get(position);
        holder.bind(device, listener);
    }

    @Override
    public int getItemCount() {
        return devices != null ? devices.size() : 0;
    }

    public void updateDevices(List<WifiP2pDevice> newDevices) {
        this.devices = newDevices;
        notifyDataSetChanged();
    }

    public static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView tvDeviceName;
        TextView tvDeviceStatus;

        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            tvDeviceStatus = itemView.findViewById(R.id.tvDeviceStatus);
        }

        public void bind(final WifiP2pDevice device, final OnDeviceClickListener listener) {
            tvDeviceName.setText(device.deviceName != null ? device.deviceName : "Unknown Device");
            tvDeviceStatus.setText(getDeviceStatus(device.status));

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeviceClick(device);
                }
            });
        }

        private String getDeviceStatus(int status) {
            switch (status) {
                case WifiP2pDevice.AVAILABLE:
                    return "Available";
                case WifiP2pDevice.INVITED:
                    return "Invited";
                case WifiP2pDevice.CONNECTED:
                    return "Connected";
                case WifiP2pDevice.FAILED:
                    return "Failed";
                case WifiP2pDevice.UNAVAILABLE:
                    return "Unavailable";
                default:
                    return "Unknown";
            }
        }
    }
}
