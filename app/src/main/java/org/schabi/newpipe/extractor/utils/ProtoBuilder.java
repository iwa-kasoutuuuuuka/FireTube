package org.schabi.newpipe.extractor.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Fire OS 7 (Android 9 / API 28) 互換性パッチ
 * Java 10+ の URLEncoder.encode(String, Charset) を呼び出す箇所の互換オーバーライド
 */
public class ProtoBuilder {
    ByteArrayOutputStream byteBuffer;

    public ProtoBuilder() {
        this.byteBuffer = new ByteArrayOutputStream();
    }

    public byte[] toBytes() {
        return this.byteBuffer.toByteArray();
    }

    public String toUrlencodedBase64() {
        String base64 = Base64.getUrlEncoder().encodeToString(toBytes());
        try {
            return URLEncoder.encode(base64, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 encoding not supported", e);
        }
    }

    private void writeVarint(long value) {
        try {
            if (value == 0L) {
                this.byteBuffer.write(new byte[]{0});
                return;
            }
            long temp = value;
            while (temp != 0L) {
                byte b = (byte) (int) (temp & 127L);
                temp >>>= 7;
                if (temp != 0L) {
                    b = (byte) (b | 128);
                }
                this.byteBuffer.write(new byte[]{b});
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void field(int fieldNumber, byte wireType) {
        long header = ((long) fieldNumber << 3) | ((long) wireType & 7L);
        writeVarint(header);
    }

    public void varint(int fieldNumber, long value) {
        field(fieldNumber, (byte) 0);
        writeVarint(value);
    }

    public void string(int fieldNumber, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        bytes(fieldNumber, bytes);
    }

    public void bytes(int fieldNumber, byte[] bytes) {
        field(fieldNumber, (byte) 2);
        writeVarint(bytes.length);
        try {
            this.byteBuffer.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
