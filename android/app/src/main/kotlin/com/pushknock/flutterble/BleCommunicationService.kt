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
import java.util.concurrent.ConcurrentHashMap

class BleCommunicationService : Service() {
    companion object {
        private const val TAG = "BleCommunicationService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ble_communication_channel"
        private const val CHANNEL_NAME = "BLE Communication Service"
        private const val WAKE_LOCK_TAG = "BleCommunicationService::WakeLock"
        
        // 표준 BLE 서비스 및 특성 UUID
        val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_CHARACTERISTIC_UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val DEVICE_INFO_SERVICE_UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val MANUFACTURER_NAME_CHARACTERISTIC_UUID = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")
    }
    
    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var isConnected = false
    private var connectedDevice: BluetoothDevice? = null
    private var discoveredServices = mutableListOf<BluetoothGattService>()
    private var characteristicMap = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    
    // 데이터 통신 콜백
    interface BleDataCallback {
        fun onDataReceived(serviceUuid: String, characteristicUuid: String, data: ByteArray)
        fun onDataWritten(serviceUuid: String, characteristicUuid: String, success: Boolean)
        fun onNotificationEnabled(serviceUuid: String, characteristicUuid: String, enabled: Boolean)
        fun onError(message: String)
    }
    
    private var bleDataCallback: BleDataCallback? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): BleCommunicationService = this@BleCommunicationService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BLE 통신 서비스 생성됨")
        
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        releaseWakeLock()
    }
    
    // 콜백 설정
    fun setDataCallback(callback: BleDataCallback) {
        this.bleDataCallback = callback
    }
    
    // BLE 연결
    fun connect(device: BluetoothDevice): Boolean {
        if (isConnected && connectedDevice?.address == device.address) {
            return true
        }
        
        disconnect()
        
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            connectedDevice = device
            Log.d(TAG, "BLE 통신 연결 시도: ${device.address}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "BLE 통신 연결 실패: ${e.message}")
            bleDataCallback?.onError("연결 실패: ${e.message}")
            return false
        }
    }
    
    // BLE 연결 해제
    fun disconnect() {
        gatt?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
                this.gatt = null
                isConnected = false
                connectedDevice = null
                discoveredServices.clear()
                characteristicMap.clear()
                Log.d(TAG, "BLE 통신 연결 해제됨")
            } catch (e: Exception) {
                Log.e(TAG, "BLE 통신 연결 해제 실패: ${e.message}")
            }
        }
    }
    
    // 연결 상태 확인
    fun isConnected(): Boolean = isConnected
    
    // 연결된 디바이스 정보 반환
    fun getConnectedDevice(): BluetoothDevice? = connectedDevice
    
    // 발견된 서비스 목록 반환
    fun getDiscoveredServices(): List<BluetoothGattService> = discoveredServices.toList()
    
    // 특정 서비스의 특성 목록 반환
    fun getCharacteristics(serviceUuid: String): List<BluetoothGattCharacteristic> {
        val service = discoveredServices.find { it.uuid.toString() == serviceUuid }
        return service?.characteristics?.toList() ?: emptyList()
    }
    
    // 데이터 읽기
    fun readCharacteristic(serviceUuid: String, characteristicUuid: String): Boolean {
        val characteristic = characteristicMap["$serviceUuid:$characteristicUuid"]
        
        if (characteristic == null) {
            bleDataCallback?.onError("특성을 찾을 수 없습니다")
            return false
        }
        
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            bleDataCallback?.onError("읽기 속성이 지원되지 않습니다")
            return false
        }
        
        return try {
            gatt?.readCharacteristic(characteristic) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "데이터 읽기 실패: ${e.message}")
            bleDataCallback?.onError("데이터 읽기 실패: ${e.message}")
            false
        }
    }
    
    // 데이터 쓰기
    fun writeCharacteristic(serviceUuid: String, characteristicUuid: String, data: ByteArray): Boolean {
        val characteristic = characteristicMap["$serviceUuid:$characteristicUuid"]
        
        if (characteristic == null) {
            bleDataCallback?.onError("특성을 찾을 수 없습니다")
            return false
        }
        
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            bleDataCallback?.onError("쓰기 속성이 지원되지 않습니다")
            return false
        }
        
        return try {
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "데이터 쓰기 실패: ${e.message}")
            bleDataCallback?.onError("데이터 쓰기 실패: ${e.message}")
            false
        }
    }
    
    // 알림(Notification) 활성화/비활성화
    fun setNotificationEnabled(serviceUuid: String, characteristicUuid: String, enabled: Boolean): Boolean {
        val characteristic = characteristicMap["$serviceUuid:$characteristicUuid"]
        
        if (characteristic == null) {
            bleDataCallback?.onError("특성을 찾을 수 없습니다")
            return false
        }
        
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
            (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            bleDataCallback?.onError("알림 속성이 지원되지 않습니다")
            return false
        }
        
        return try {
            val success = gatt?.setCharacteristicNotification(characteristic, enabled) ?: false
            if (success && enabled) {
                // Descriptor 설정 (알림 활성화를 위해 필요)
                val descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
                )
                if (descriptor != null) {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt?.writeDescriptor(descriptor)
                }
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "알림 설정 실패: ${e.message}")
            bleDataCallback?.onError("알림 설정 실패: ${e.message}")
            false
        }
    }
    
    // 배터리 레벨 읽기 (예시)
    fun readBatteryLevel(): Boolean {
        return readCharacteristic(
            BATTERY_SERVICE_UUID.toString(),
            BATTERY_LEVEL_CHARACTERISTIC_UUID.toString()
        )
    }
    
    // 제조사 이름 읽기 (예시)
    fun readManufacturerName(): Boolean {
        return readCharacteristic(
            DEVICE_INFO_SERVICE_UUID.toString(),
            MANUFACTURER_NAME_CHARACTERISTIC_UUID.toString()
        )
    }
    
    // GATT 콜백
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "BLE 통신 연결됨")
                    isConnected = true
                    updateNotification("BLE 통신 연결됨: ${connectedDevice?.name ?: "Unknown"}")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "BLE 통신 연결 해제됨")
                    isConnected = false
                    updateNotification("BLE 통신 연결 해제됨")
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "BLE 서비스 발견됨")
                discoveredServices.clear()
                characteristicMap.clear()
                
                gatt.services.forEach { service ->
                    discoveredServices.add(service)
                    service.characteristics.forEach { characteristic ->
                        val key = "${service.uuid}:${characteristic.uuid}"
                        characteristicMap[key] = characteristic
                        Log.d(TAG, "특성 발견: $key, 속성: ${characteristic.properties}")
                    }
                }
                
                Log.d(TAG, "총 ${discoveredServices.size}개 서비스, ${characteristicMap.size}개 특성 발견")
            } else {
                Log.e(TAG, "BLE 서비스 발견 실패: $status")
                bleDataCallback?.onError("서비스 발견 실패: $status")
            }
        }
        
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                val serviceUuid = characteristic.service.uuid.toString()
                val characteristicUuid = characteristic.uuid.toString()
                
                Log.d(TAG, "데이터 읽기 성공: $serviceUuid:$characteristicUuid, 데이터: ${data.contentToString()}")
                bleDataCallback?.onDataReceived(serviceUuid, characteristicUuid, data)
            } else {
                Log.e(TAG, "데이터 읽기 실패: $status")
                bleDataCallback?.onError("데이터 읽기 실패: $status")
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val serviceUuid = characteristic.service.uuid.toString()
                val characteristicUuid = characteristic.uuid.toString()
                
                Log.d(TAG, "데이터 쓰기 성공: $serviceUuid:$characteristicUuid")
                bleDataCallback?.onDataWritten(serviceUuid, characteristicUuid, true)
            } else {
                Log.e(TAG, "데이터 쓰기 실패: $status")
                val serviceUuid = characteristic.service.uuid.toString()
                val characteristicUuid = characteristic.uuid.toString()
                bleDataCallback?.onDataWritten(serviceUuid, characteristicUuid, false)
                bleDataCallback?.onError("데이터 쓰기 실패: $status")
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val serviceUuid = characteristic.service.uuid.toString()
            val characteristicUuid = characteristic.uuid.toString()
            
            Log.d(TAG, "알림 수신: $serviceUuid:$characteristicUuid, 데이터: ${value.contentToString()}")
            bleDataCallback?.onDataReceived(serviceUuid, characteristicUuid, value)
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor 쓰기 성공")
            } else {
                Log.e(TAG, "Descriptor 쓰기 실패: $status")
            }
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
            .setContentTitle("BLE 통신 서비스")
            .setContentText("BLE 데이터 통신을 수행하고 있습니다")
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
            .setContentTitle("BLE 통신 서비스")
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
                description = "BLE 통신 서비스 알림"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
} 