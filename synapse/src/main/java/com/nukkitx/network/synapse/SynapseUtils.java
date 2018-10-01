package com.nukkitx.network.synapse;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nukkitx.network.util.Preconditions;
import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;
import net.minidev.json.JSONObject;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

@UtilityClass
public class SynapseUtils {
    private static final int MAGIC = 0xDEADC0DE;

    public static void readMagic(ByteBuf buf) {
        Preconditions.checkArgument(buf.readInt() == MAGIC, "Invalid magic");
    }

    public static void writeMagic(ByteBuf buf) {
        buf.writeInt(MAGIC);
    }

    public static byte[] readByteArray(ByteBuf buf) {
        int length = buf.readInt();
        byte[] bytes = new byte[length];
        buf.readBytes(length);
        return bytes;
    }

    public static void writeByteArray(ByteBuf buf, byte[] bytes) {
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(ByteBuf buf) {
        return new String(readByteArray(buf), StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String string) {
        writeByteArray(buf, string.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID readUuid(ByteBuf buf) {
        long most = buf.readLong();
        long least = buf.readLong();
        return new UUID(most, least);
    }

    public static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }

    public static SecretKey generateSecretKey(String password) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("MD5 algorithm doesn't exist");
        }

        byte[] digested = digest.digest(password.getBytes(StandardCharsets.UTF_8));

        return new SecretKeySpec(digested, "AES");
    }

    public static String encryptConnect(SecretKey key, JSONObject json) throws JOSEException {
        JWEHeader header = new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A128GCM);
        Payload payload = new Payload(json);

        JWEObject jweObject = new JWEObject(header, payload);
        jweObject.encrypt(new DirectEncrypter(key));

        return jweObject.serialize();
    }

    public static Optional<JSONObject> decryptConnect(SecretKey key, String serialized) {
        try {
            JWEObject jweObject = JWEObject.parse(serialized);

            jweObject.decrypt(new DirectDecrypter(key));

            return Optional.of(jweObject.getPayload().toJSONObject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
