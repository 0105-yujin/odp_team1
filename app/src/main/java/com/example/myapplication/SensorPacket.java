package com.example.myapplication; // ★본인 패키지명 유지

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SensorPacket {
    public float temperature; // 온도 (°C)
    public float humidity;    // 습도 (%)
    public int aqi;           // 공기질 지수 (1~5)
    public int tvoc;          // TVOC (ppb)
    public int eco2;          // eCO2 (ppm)
    public long timestamp;    // Unix 타임스탬프
    public String hmacTag;    // ★ 8바이트 HMAC 태그 추가됨

    public static SensorPacket parse(byte[] data) {
        // 기존 13바이트에서 21바이트(13 + 태그 8) 이상으로 유효성 검사 변경
        if (data == null || data.length < 21) {
            return null;
        }

        // 라즈베리파이 Little-Endian 방식 처리
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        SensorPacket p = new SensorPacket();

        // 0~12바이트: 센서 데이터
        p.temperature = buf.getShort() / 100.0f;
        p.humidity = (buf.getShort() & 0xFFFF) / 100.0f;
        p.aqi = buf.get() & 0xFF;
        p.tvoc = buf.getShort() & 0xFFFF;
        p.eco2 = buf.getShort() & 0xFFFF;
        p.timestamp = buf.getInt() & 0xFFFFFFFFL;

        // 13~20바이트: HMAC 태그 8바이트 추출 및 16진수 문자열(Hex String)로 변환
        byte[] tagBytes = new byte[8];
        buf.position(13);
        buf.get(tagBytes, 0, 8);

        StringBuilder sb = new StringBuilder();
        for (byte b : tagBytes) {
            sb.append(String.format("%02x", b));
        }
        p.hmacTag = sb.toString();

        return p;
    }

    @Override
    public String toString() {
        return String.format(
                "온도: %.2f°C | 습도: %.2f%% | AQI: %d | TVOC: %d ppb | eCO2: %d ppm\nHMAC 태그: %s",
                temperature, humidity, aqi, tvoc, eco2, hmacTag
        );
    }
}