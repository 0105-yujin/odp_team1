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
import android.location.Location;
import android.location.LocationManager;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private LocationManager locationManager;

    // 버튼을 누를 때까지 데이터를 임시로 모아둘 리스트
    private List<String> pendingCsvData = new ArrayList<>();

    // ★ 서버 전송용: 스캔된 값을 마지막 1건만이 아니라 전부 모아둠 (전송 버튼 누를 때 전부 보냄)
    private static class PendingScan {
        final SensorPacket packet;
        final String deviceAddress;
        final byte[] rawBytes;
        PendingScan(SensorPacket packet, String deviceAddress, byte[] rawBytes) {
            this.packet = packet;
            this.deviceAddress = deviceAddress;
            this.rawBytes = rawBytes;
        }
    }
    private final List<PendingScan> pendingScans = new ArrayList<>();

    private Retrofit retrofit;

    @Override
    protected void onCreate(Bundle savedBundleInstance) {
        super.onCreate(savedBundleInstance);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);

        Gson gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl("http://203.255.81.72:10021/") // ★수정: API 슬라이드 기준 주소/포트로 변경 (기존 10.255.81.72:10024)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        // 권한 체크 및 초기화
        bleInitialize(this);

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        initBluetoothLeScanner();

        Button btnScan = findViewById(R.id.btnScan);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnSave = findViewById(R.id.btnSave); // 저장 버튼 연결
        Button btnSend = findViewById(R.id.btnSend);

        btnScan.setOnClickListener(v -> startScanning());
        btnStop.setOnClickListener(v -> stopScanning());
        btnSave.setOnClickListener(v -> saveBufferedData());
        btnSend.setOnClickListener(v -> sendDataToServer());// 저장 버튼 클릭 시 실행
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 블루투스를 앱 실행 후에 켰거나 권한을 뒤늦게 허용한 경우를 대비해 재확인
        initBluetoothLeScanner();
    }

    private void initBluetoothLeScanner() {
        blead = BluetoothAdapter.getDefaultAdapter();
        if (blead == null) {
            bluetoothLeScanner = null;
            return;
        }
        if (blead.isEnabled()) {
            bluetoothLeScanner = blead.getBluetoothLeScanner();
        } else {
            bluetoothLeScanner = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            initBluetoothLeScanner();
        } else {
            Toast.makeText(this, "블루투스/위치 권한이 없으면 스캔할 수 없습니다.", Toast.LENGTH_LONG).show();
        }
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
            Toast.makeText(this, "블루투스 권한이 없습니다. 권한을 허용해주세요.", Toast.LENGTH_SHORT).show();
            requestBlePermissions(this);
            return;
        }

        // 스캐너가 아직 준비되지 않았다면(블루투스가 나중에 켜진 경우 등) 재시도
        if (bluetoothLeScanner == null) {
            initBluetoothLeScanner();
        }
        if (blead == null || !blead.isEnabled()) {
            Toast.makeText(this, "블루투스가 꺼져 있습니다. 켜주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "블루투스 스캐너를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        // ★ ScanFilter.setServiceUuid()는 광고 패킷의 "서비스 UUID 목록" AD 필드만 검사한다.
        // 센서가 UUID를 Service Data 필드에만 실어 보내면 이 필터에 안 걸려 onScanResult가 전혀 호출되지 않는다.
        // 필터링은 onScanResult 안에서 기기 이름으로 소프트웨어적으로 하므로 OS 레벨 필터는 걸지 않는다.
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bluetoothLeScanner.startScan(null, scanSettings, scanCallback);
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

            if (deviceName == null || !deviceName.equals("opensrc_week_3")) return;

            byte[] scanRecordBytes = scanRecord != null ? scanRecord.getServiceData(ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb")) : null;

            if (scanRecordBytes != null) {
                SensorPacket packet = SensorPacket.parse(scanRecordBytes);
                if (packet != null) {
                    String logStr = "\n[수신] 이름: " + deviceName + ", MAC: " + deviceAddress + ", RSSI: " + rssi + "\n" + packet.toString()
                            + "\nRAW: " + bytesToHex(scanRecordBytes);
                    tvLog.append(logStr);

                    // ★ 진단용: 원본 바이트를 그대로 로그에 남겨서 AQI/TVOC/eCO2 자리(byte[4], byte[5-6], byte[7-8])가
                    // 수신 시점에 실제로 0인지, 파싱 과정에서 0이 되는지 바로 눈으로 확인할 수 있게 함.
                    tvLog.append("\nRAW(" + scanRecordBytes.length + "B): " + bytesToHex(scanRecordBytes)
                            + "\n  ㄴ aqi byte[4]=" + String.format("%02x", scanRecordBytes[4])
                            + ", tvoc byte[5-6]=" + String.format("%02x%02x", scanRecordBytes[6], scanRecordBytes[5])
                            + ", eco2 byte[7-8]=" + String.format("%02x%02x", scanRecordBytes[8], scanRecordBytes[7]));

                    // ★ 습도, AQI, TVOC, HMAC 태그까지 모두 포함하여 임시 보관
                    String csvLine = packet.timestamp + "," + deviceName + "," + deviceAddress + "," + rssi + ",0x181A," + packet.eco2 + "," + packet.temperature + "," + packet.humidity + "," + packet.aqi + "," + packet.tvoc + "," + packet.hmacTag + "\n";
                    pendingCsvData.add(csvLine);

                    pendingScans.add(new PendingScan(packet, deviceAddress, scanRecordBytes));
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
    private Location getLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null && (best == null || location.getTime() > best.getTime())) {
                    best = location;
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
                // 기기에 해당 provider가 없거나 권한이 없는 경우
            }
        }
        return best;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void sendDataToServer() {
        if (pendingScans.isEmpty()) {
            Toast.makeText(this, "전송할 데이터가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        ApiService apiService = retrofit.create(ApiService.class);

        Location location = getLastKnownLocation();
        double lat = location != null ? location.getLatitude() : 0.0;
        double lon = location != null ? location.getLongitude() : 0.0;

        // ★ 스캔 중 쌓인 값을 전부 서버로 전송 (기존엔 마지막 1건만 보내서 전송 누락 발생)
        List<PendingScan> toSend = new ArrayList<>(pendingScans);
        pendingScans.clear();

        int total = toSend.size();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        tvLog.append("\n\n[전송 시작] 총 " + total + "건");

        for (PendingScan scan : toSend) {
            SensorRequest request = new SensorRequest(
                    "opensrc2026",      // key
                    "team 1",   // ★수정: 실제 팀 번호로 변경 필요 (예: "team 1" → 본인 팀에 맞게)
                    "opensrc_week_3",        // sensor - 센서 이름
                    scan.deviceAddress,  // mac - 실제 센서 맥주소
                    scan.packet.temperature,
                    scan.packet.humidity,
                    scan.packet.aqi,
                    scan.packet.tvoc,
                    scan.packet.eco2,
                    scan.packet.timestamp,
                    lat,
                    lon,
                    deviceId,           // sender
                    bytesToHex(scan.rawBytes) // raw - 서버 검증용 원본 패킷 바이트
            );

            apiService.sendSensorData(request).enqueue(new Callback<SensorResponse>() {
                @Override
                public void onResponse(Call<SensorResponse> call, Response<SensorResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        successCount.incrementAndGet();
                        tvLog.append("\n[서버 응답] " + response.body().getResult() + " - " + response.body().getMessage());
                    } else {
                        failCount.incrementAndGet();
                        String errorDetail;
                        try {
                            errorDetail = response.errorBody() != null ? response.errorBody().string() : "(본문 없음)";
                        } catch (IOException e) {
                            errorDetail = "(에러 본문 읽기 실패: " + e.getMessage() + ")";
                        }
                        tvLog.append("\n[서버 응답 오류] HTTP " + response.code() + " - " + errorDetail);
                    }
                    reportSendProgress(successCount, failCount, total);
                }

                @Override
                public void onFailure(Call<SensorResponse> call, Throwable t) {
                    failCount.incrementAndGet();
                    tvLog.append("\n[전송 실패] " + t.getMessage());
                    reportSendProgress(successCount, failCount, total);
                }
            });
        }
    }

    private void reportSendProgress(AtomicInteger successCount, AtomicInteger failCount, int total) {
        int done = successCount.get() + failCount.get();
        if (done == total) {
            Toast.makeText(this, "전송 완료: 성공 " + successCount.get() + "건 / 실패 " + failCount.get() + "건", Toast.LENGTH_LONG).show();
            tvLog.append("\n[전송 완료] 성공 " + successCount.get() + "건 / 실패 " + failCount.get() + "건");
        }
    }
}