package com.example.myapplication; // ★주의: 본인 프로젝트 패키지명으로 유지하세요.

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import android.provider.Settings;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE_S = 101;
    private static final int PERMISSION_REQUEST_CODE = 100;

    private BluetoothAdapter blead;
    private BluetoothLeScanner bluetoothLeScanner;
    private TextView tvLog;

    // 버튼을 누를 때까지 데이터를 임시로 모아둘 리스트
    private List<String> pendingCsvData = new ArrayList<>();

    private Retrofit retrofit;

    @Override
    protected void onCreate(Bundle savedBundleInstance) {
        super.onCreate(savedBundleInstance);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);

        Gson gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl("http://10.255.81.72:10024/")
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        // 권한 체크 및 초기화
        bleInitialize(this);

        blead = BluetoothAdapter.getDefaultAdapter();
        if (blead != null && blead.isEnabled()) {
            bluetoothLeScanner = blead.getBluetoothLeScanner();
        }

        Button btnScan = findViewById(R.id.btnScan);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnSave = findViewById(R.id.btnSave); // 저장 버튼 연결
        Button btnSend = findViewById(R.id.btnSend);

        btnScan.setOnClickListener(v -> startScanning());
        btnStop.setOnClickListener(v -> stopScanning());
        btnSave.setOnClickListener(v -> saveBufferedData());
        btnSend.setOnClickListener(v -> sendDataToServer());// 저장 버튼 클릭 시 실행
    }

    private void bleInitialize(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBlePermissions(activity);
            }
        } else {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBlePermissions(activity);
            }
        }
    }

    private void requestBlePermissions(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE_S);
        } else {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        }
    }

    private void startScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return;
        }
        if (bluetoothLeScanner == null) return;

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb")).build());

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothLeScanner.startScan(filters, scanSettings, scanCallback);
        tvLog.append("\n\n--- 스캔 시작 ---");
    }

    private void stopScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return;
        }
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
            tvLog.append("\n\n--- 스캔 중지됨 ---");
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord scanRecord = result.getScanRecord();
            int rssi = result.getRssi();

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return;

            String deviceName = device.getName();
            String deviceAddress = device.getAddress();

            byte[] scanRecordBytes = scanRecord != null ? scanRecord.getServiceData(ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb")) : null;

            if (deviceName != null && deviceName.equals("opensrc_week_3") && scanRecordBytes != null) {
                SensorPacket packet = SensorPacket.parse(scanRecordBytes);
                if (packet != null) {
                    String logStr = "\n[수신] 이름: " + deviceName + ", MAC: " + deviceAddress + ", RSSI: " + rssi + "\n" + packet.toString();
                    tvLog.append(logStr);

                    // ★ 습도, AQI, TVOC, HMAC 태그까지 모두 포함하여 임시 보관
                    String csvLine = packet.timestamp + "," + deviceName + "," + deviceAddress + "," + rssi + ",0x181A," + packet.eco2 + "," + packet.temperature + "," + packet.humidity + "," + packet.aqi + "," + packet.tvoc + "," + packet.hmacTag + "\n";
                    pendingCsvData.add(csvLine);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            tvLog.append("\n스캔 에러 발생 코드: " + errorCode);
        }
    };

    private void saveBufferedData() {
        if (pendingCsvData.isEmpty()) {
            Toast.makeText(this, "저장할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File dir = getExternalFilesDir(null);
            File file = new File(dir, "ble_data.csv");
            boolean fileExists = file.exists();
            FileWriter fw = new FileWriter(file, true);

            // ★ 모든 데이터에 맞게 CSV 컬럼 헤더(맨 윗줄) 변경
            if (!fileExists) {
                fw.append("timestamp,device_name,device_address,rssi,uuid,co2,temperature,humidity,aqi,tvoc,hmactag\n");
            }

            for (String line : pendingCsvData) {
                fw.append(line);
            }

            fw.flush();
            fw.close();

            int savedCount = pendingCsvData.size();
            pendingCsvData.clear();

            Toast.makeText(this, savedCount + "건의 데이터가 저장되었습니다.", Toast.LENGTH_SHORT).show();
            tvLog.append("\n\n[알림] " + savedCount + "건의 데이터 CSV 저장 완료!");

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "저장 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show();
        }
    }
    private void sendDataToServer() {
        if (pendingCsvData.isEmpty()) {
            Toast.makeText(this, "전송할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        ApiService apiService = retrofit.create(ApiService.class);

        // 예시: 가장 최근 스캔 결과 하나를 보낸다고 가정 (실제 값은 상황에 맞게 채워야 함)
        SensorRequest request = new SensorRequest(
                "opensrc2026",      // key
                "team TA",          // team - 본인 팀 번호로 변경
                "sensor TA",        // sensor - 센서 이름
                "AA:BB:CC:DD:EE:FF",// mac - 실제 센서 맥주소로 변경
                11,                 // temp
                22,                 // humidity
                33,                 // AQI
                44,                 // TVOC
                55,                 // eCO2
                System.currentTimeMillis(), // timestamp
                7.7,                // lat
                8.8,                // lon
                deviceId            // sender
        );

        apiService.sendSensorData(request).enqueue(new Callback<SensorResponse>() {
            @Override
            public void onResponse(Call<SensorResponse> call, Response<SensorResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Toast.makeText(MainActivity.this, response.body().getMessage(), Toast.LENGTH_SHORT).show();
                    tvLog.append("\n[서버 응답] " + response.body().getResult() + " - " + response.body().getMessage());
                } else {
                    Toast.makeText(MainActivity.this, "서버 응답 오류", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<SensorResponse> call, Throwable t) {
                Toast.makeText(MainActivity.this, "전송 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                tvLog.append("\n[전송 실패] " + t.getMessage());
            }
        });
    }
}

