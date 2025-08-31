package com.pushknock.flutterble

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.*

class MainActivity : FlutterActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL = "flutter_ble_communication_channel"
    }
    
    private var bleCommunicationService: BleCommunicationService? = null
    private var bleScannerService: BleScannerService? = null
    private var isCommunicationServiceBound = false
    private var isScannerServiceBound = false
    
    private val communicationServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BleCommunicationService.LocalBinder
            bleCommunicationService = binder.getService()
            isCommunicationServiceBound = true
            
            // BLE 데이터 콜백 설정
            bleCommunicationService?.setDataCallback(object : BleCommunicationService.BleDataCallback {
                override fun onDataReceived(serviceUuid: String, characteristicUuid: String, data: ByteArray) {
                    sendToFlutter("dataReceived", mapOf(
                        "serviceUuid" to serviceUuid,
                        "characteristicUuid" to characteristicUuid,
                        "data" to data.map { it.toInt() }
                    ))
                }
                
                override fun onDataWritten(serviceUuid: String, characteristicUuid: String, success: Boolean) {
                    sendToFlutter("dataWritten", mapOf(
                        "serviceUuid" to serviceUuid,
                        "characteristicUuid" to characteristicUuid,
                        "success" to success
                    ))
                }
                
                override fun onNotificationEnabled(serviceUuid: String, characteristicUuid: String, enabled: Boolean) {
                    sendToFlutter("notificationEnabled", mapOf(
                        "serviceUuid" to serviceUuid,
                        "characteristicUuid" to characteristicUuid,
                        "enabled" to enabled
                    ))
                }
                
                override fun onError(message: String) {
                    sendToFlutter("error", mapOf("message" to message))
                }
            })
            
            Log.d(TAG, "BLE 통신 서비스에 연결됨")
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            isCommunicationServiceBound = false
            bleCommunicationService = null
            Log.d(TAG, "BLE 통신 서비스 연결 해제됨")
        }
    }
    
    private val scannerServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BleScannerService.LocalBinder
            bleScannerService = binder.getService()
            isScannerServiceBound = true
            
            // BLE 스캔 콜백 설정
            bleScannerService?.setScanCallback(object : BleScannerService.BleScanCallback {
                override fun onDeviceDiscovered(device: BluetoothDevice, rssi: Int) {
                    sendToFlutter("deviceDiscovered", mapOf(
                        "address" to device.address,
                        "name" to (device.name ?: "Unknown"),
                        "rssi" to rssi
                    ))
                }
                
                override fun onScanStarted() {
                    sendToFlutter("scanStarted", emptyMap<String, Any>())
                }
                
                override fun onScanStopped() {
                    sendToFlutter("scanStopped", emptyMap<String, Any>())
                }
                
                override fun onError(message: String) {
                    sendToFlutter("scanError", mapOf("message" to message))
                }
            })
            
            Log.d(TAG, "BLE 스캐너 서비스에 연결됨")
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            isScannerServiceBound = false
            bleScannerService = null
            Log.d(TAG, "BLE 스캐너 서비스 연결 해제됨")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAndBindBleServices()
    }
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                // 스캔 관련 메서드
                "startScan" -> {
                    if (bleScannerService?.startScan() == true) {
                        result.success(true)
                    } else {
                        result.error("SCAN_ERROR", "스캔을 시작할 수 없습니다", null)
                    }
                }
                "stopScan" -> {
                    bleScannerService?.stopScan()
                    result.success(true)
                }
                "isScanning" -> {
                    result.success(bleScannerService?.isScanning() ?: false)
                }
                "getDiscoveredDevices" -> {
                    val devices = bleScannerService?.getDiscoveredDevices()?.map { device ->
                        mapOf(
                            "address" to device.address,
                            "name" to (device.name ?: "Unknown")
                        )
                    } ?: emptyList()
                    result.success(devices)
                }
                
                // 연결 관련 메서드
                "connect" -> {
                    val address = call.argument<String>("address")
                    if (address != null) {
                        val device = bleScannerService?.findDeviceByAddress(address)
                        if (device != null) {
                            if (bleCommunicationService?.connect(device) == true) {
                                result.success(true)
                            } else {
                                result.error("CONNECT_ERROR", "연결할 수 없습니다", null)
                            }
                        } else {
                            result.error("DEVICE_NOT_FOUND", "디바이스를 찾을 수 없습니다", null)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "주소가 필요합니다", null)
                    }
                }
                "disconnect" -> {
                    bleCommunicationService?.disconnect()
                    result.success(true)
                }
                "isConnected" -> {
                    result.success(bleCommunicationService?.isConnected() ?: false)
                }
                "getConnectedDevice" -> {
                    val device = bleCommunicationService?.getConnectedDevice()
                    if (device != null) {
                        result.success(mapOf(
                            "address" to device.address,
                            "name" to (device.name ?: "Unknown")
                        ))
                    } else {
                        result.success(null)
                    }
                }
                
                // 데이터 통신 관련 메서드
                "getDiscoveredServices" -> {
                    val services = bleCommunicationService?.getDiscoveredServices()?.map { service ->
                        mapOf(
                            "uuid" to service.uuid.toString(),
                            "characteristics" to service.characteristics.map { characteristic ->
                                mapOf(
                                    "uuid" to characteristic.uuid.toString(),
                                    "properties" to characteristic.properties
                                )
                            }
                        )
                    } ?: emptyList()
                    result.success(services)
                }
                "readCharacteristic" -> {
                    val serviceUuid = call.argument<String>("serviceUuid")
                    val characteristicUuid = call.argument<String>("characteristicUuid")
                    
                    if (serviceUuid != null && characteristicUuid != null) {
                        val success = bleCommunicationService?.readCharacteristic(serviceUuid, characteristicUuid) ?: false
                        result.success(success)
                    } else {
                        result.error("INVALID_ARGUMENT", "서비스 UUID와 특성 UUID가 필요합니다", null)
                    }
                }
                "writeCharacteristic" -> {
                    val serviceUuid = call.argument<String>("serviceUuid")
                    val characteristicUuid = call.argument<String>("characteristicUuid")
                    val data = call.argument<List<Int>>("data")
                    
                    if (serviceUuid != null && characteristicUuid != null && data != null) {
                        val byteArray = data.map { it.toByte() }.toByteArray()
                        val success = bleCommunicationService?.writeCharacteristic(serviceUuid, characteristicUuid, byteArray) ?: false
                        result.success(success)
                    } else {
                        result.error("INVALID_ARGUMENT", "서비스 UUID, 특성 UUID, 데이터가 필요합니다", null)
                    }
                }
                "setNotificationEnabled" -> {
                    val serviceUuid = call.argument<String>("serviceUuid")
                    val characteristicUuid = call.argument<String>("characteristicUuid")
                    val enabled = call.argument<Boolean>("enabled")
                    
                    if (serviceUuid != null && characteristicUuid != null && enabled != null) {
                        val success = bleCommunicationService?.setNotificationEnabled(serviceUuid, characteristicUuid, enabled) ?: false
                        result.success(success)
                    } else {
                        result.error("INVALID_ARGUMENT", "서비스 UUID, 특성 UUID, 활성화 여부가 필요합니다", null)
                    }
                }
                "readBatteryLevel" -> {
                    val success = bleCommunicationService?.readBatteryLevel() ?: false
                    result.success(success)
                }
                "readManufacturerName" -> {
                    val success = bleCommunicationService?.readManufacturerName() ?: false
                    result.success(success)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }
    
    private fun startAndBindBleServices() {
        // BLE 스캐너 서비스 시작 및 바인딩
        val scannerServiceIntent = Intent(this, BleScannerService::class.java)
        startService(scannerServiceIntent)
        bindService(scannerServiceIntent, scannerServiceConnection, Context.BIND_AUTO_CREATE)
        
        // BLE 통신 서비스 시작 및 바인딩
        val communicationServiceIntent = Intent(this, BleCommunicationService::class.java)
        startService(communicationServiceIntent)
        bindService(communicationServiceIntent, communicationServiceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun sendToFlutter(method: String, arguments: Map<String, Any>) {
        Log.d(TAG, "Flutter로 전송: $method, $arguments")
        // EventChannel을 사용하여 Flutter에 실시간 데이터 전송
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isCommunicationServiceBound) {
            unbindService(communicationServiceConnection)
            isCommunicationServiceBound = false
        }
        if (isScannerServiceBound) {
            unbindService(scannerServiceConnection)
            isScannerServiceBound = false
        }
    }
}
