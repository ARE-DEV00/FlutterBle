package com.pushknock.flutterble

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class BleScannerService : Service() {
    companion object {
        private const val TAG = "BleScannerService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ble_scanner_channel"
        private const val CHANNEL_NAME = "BLE Scanner Service"
        private const val WAKE_LOCK_TAG = "BleScannerService::WakeLock"
    }
    
    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var isScanning = false
    
    // 스캔 콜백 인터페이스
    interface BleScanCallback {
        fun onDeviceDiscovered(device: BluetoothDevice, rssi: Int)
        fun onScanStarted()
        fun onScanStopped()
        fun onError(message: String)
    }
    
    private var bleScanCallback: BleScanCallback? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): BleScannerService = this@BleScannerService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BLE 스캐너 서비스 생성됨")
        
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        releaseWakeLock()
    }
    
    // 콜백 설정
    fun setScanCallback(callback: BleScanCallback) {
        this.bleScanCallback = callback
    }
    
    // BLE 스캔 시작
    fun startScan(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            bleScanCallback?.onError("블루투스가 활성화되지 않았습니다")
            return false
        }
        
        if (isScanning) {
            return false
        }
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        val scanFilter = ScanFilter.Builder()
            .build()
        
        try {
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            discoveredDevices.clear()
            bleScanCallback?.onScanStarted()
            updateNotification("BLE 스캔 중...")
            Log.d(TAG, "BLE 스캔 시작")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "스캔 시작 실패: ${e.message}")
            bleScanCallback?.onError("스캔 시작 실패: ${e.message}")
            return false
        }
    }
    
    // BLE 스캔 중지
    fun stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                isScanning = false
                bleScanCallback?.onScanStopped()
                updateNotification("BLE 스캔 중지됨")
                Log.d(TAG, "BLE 스캔 중지")
            } catch (e: Exception) {
                Log.e(TAG, "스캔 중지 실패: ${e.message}")
            }
        }
    }
    
    // 현재 스캔 상태 확인
    fun isScanning(): Boolean = isScanning
    
    // 발견된 디바이스 목록 반환
    fun getDiscoveredDevices(): List<BluetoothDevice> = discoveredDevices.toList()
    
    // 특정 주소의 디바이스 찾기
    fun findDeviceByAddress(address: String): BluetoothDevice? {
        return discoveredDevices.find { it.address == address }
    }
    
    // 스캔 콜백
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                bleScanCallback?.onDeviceDiscovered(device, rssi)
                Log.d(TAG, "디바이스 발견: ${device.address}, RSSI: $rssi")
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "스캔 실패: $errorCode")
            bleScanCallback?.onError("스캔 실패: $errorCode")
            isScanning = false
            updateNotification("BLE 스캔 실패")
        }
    }
    
    // Wake Lock 획득
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            acquire(10*60*1000L)
        }
    }
    
    // Wake Lock 해제
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }
    
    // 알림 생성
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE 스캐너 서비스")
            .setContentText("BLE 디바이스 스캔을 수행하고 있습니다")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        return notification
    }
    
    // 알림 업데이트
    private fun updateNotification(contentText: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE 스캐너 서비스")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    // 알림 채널 생성
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE 스캐너 서비스 알림"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
} 